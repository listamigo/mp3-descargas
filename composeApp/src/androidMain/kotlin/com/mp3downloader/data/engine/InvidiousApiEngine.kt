package com.mp3downloader.data.engine

import com.mp3downloader.data.dto.InvidiousSearchItem
import com.mp3downloader.data.dto.InvidiousVideoResponse
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.M4aMetadataWriter
import com.mp3downloader.domain.service.ThumbnailQualityResolver
import com.mp3downloader.domain.service.isSafeHttpsUrl
import com.mp3downloader.domain.service.isValidYouTubeId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class InvidiousApiEngine : DownloadEngine {

    /** Reject absurdly large responses to avoid filling the device storage. */
    private val maxDownloadBytes = 250L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {
        install(HttpRedirect)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 8_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            header(HttpHeaders.Accept, "application/json")
        }
    }

    private var activeUrl: String? = null
    private var instanceVerified = false
    private val activeDownloads = mutableMapOf<String, Boolean>()

    /**
     * The official directory (api.invidious.io) currently reports a single
     * instance with the API enabled and answering, verified end to end: search,
     * /api/v1/videos and full audio download. Instances go down constantly, so
     * this list is intentionally short to keep resolution fast, and the user
     * can override it in Settings.
     */
    private val defaultInstances = listOf(
        "https://invidious.f5.si",
    )

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        val result = runCatching {
            val url = resolveInstance()
            android.util.Log.d("InvidiousApi", "search: using instance=$url")
            val page = (offset / SEARCH_PAGE_SIZE) + 1
            val raw = httpClient
                .get("$url/api/v1/search") {
                    url {
                        parameters.append("q", query)
                        parameters.append("page", page.toString())
                    }
                }
                .bodyAsText()
            android.util.Log.d("InvidiousApi", "search: response length=${raw.length}")
            if (raw.trimStart().startsWith("<")) {
                val preview = raw.take(100).replace("\n", " ")
                throw RuntimeException("Invidious ($url) devolvió HTML (bloqueado o caída). Respuesta: $preview")
            }
            val items = json.decodeFromString<List<InvidiousSearchItem>>(raw)
            val songs = items.mapNotNull { item ->
                // La búsqueda de Invidious devuelve también canales y playlists,
                // sin videoId ni title. Se descartan aquí; el DTO ya no revienta
                // al parsearlos.
                val videoId = item.videoId
                val title = item.title
                if (videoId == null || title == null || title.isBlank() || !isValidYouTubeId(videoId)) {
                    android.util.Log.d("InvidiousApi", "search: descartado resultado sin videoId (type=${item.author})")
                    return@mapNotNull null
                }
                Song(
                    id = videoId,
                    title = title,
                    artist = item.author ?: "Unknown",
                    duration = (item.lengthSeconds ?: 0L),
                    // La miniatura se pide SIEMPRE al CDN de YouTube, no a la
                    // instancia: Invidious sirve las suyas con su propio proxy de
                    // imágenes y, cuando ese proxy (o el companion) cae, responde
                    // HTTP 200 con una página HTML en lugar de un JPEG. El
                    // BitmapFactory no la decodifica y se quedaba sin miniatura.
                    // El id ya viene validado por isValidYouTubeId.
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    audioUrl = null
                )
            }
            android.util.Log.d("InvidiousApi", "search: ${songs.size} results")
            songs
        }
        if (result.isFailure) invalidateInstance()
        return result
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        val result = runCatching {
            val url = resolveInstance()
            val raw = httpClient
                .get("$url/api/v1/videos/${song.id}")
                .bodyAsText()

            if (raw.trimStart().startsWith("<")) {
                val preview = raw.take(100).replace("\n", " ")
                throw RuntimeException("Invidious ($url) devolvió HTML (bloqueado). Respuesta: $preview")
            }

            val element = json.parseToJsonElement(raw)

            // El companion de Invidious contesta HTTP 200 con {"error": ...}
            // cuando no consigue hablar con YouTube (caída muy frecuente). Sin
            // este chequeo el DTO separseaba con todos los campos a null y el
            // usuario veía "Sin streams de audio", que apunta a otra causa.
            val errorMsg = (element as? JsonObject)?.get("error")
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (!errorMsg.isNullOrBlank()) {
                throw RuntimeException("Invidious ($url) no pudo resolver el video: $errorMsg")
            }

            val video = json.decodeFromJsonElement<InvidiousVideoResponse>(element)

            val formats = video.adaptiveFormats ?: video.formatStreams ?: emptyList()
            val bestAudio = formats
                .filter {
                    (it.type?.contains("audio/mp4") == true) ||
                    (it.mimeType?.contains("audio/mp4") == true) ||
                    (it.type?.contains("m4a") == true)
                }
                .maxByOrNull { it.bitrate ?: 0 }
                ?: formats.firstOrNull()
                ?: throw RuntimeException("Sin streams de audio disponibles en Invidious ($url)")

            val audioUrl = bestAudio.url
            if (!isSafeHttpsUrl(audioUrl)) {
                throw RuntimeException("URL de audio insegura recibida de Invidious")
            }
            audioUrl
        }
        if (result.isFailure) invalidateInstance()
        return result
    }

    override fun download(
        song: Song,
        outputDir: String
    ): Flow<DownloadResult> = flow {
        val audioUrlResult = getAudioStreamUrl(song)
        if (audioUrlResult.isFailure) {
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = audioUrlResult.exceptionOrNull()?.message))
            return@flow
        }
        val audioUrl = audioUrlResult.getOrThrow()

        emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, 0f))

        val safeTitle = song.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val outputFile = uniqueOutputFile(outputDir, safeTitle, "m4a")

        try {
            activeDownloads[song.id] = true

            val response: HttpResponse = httpClient.get(audioUrl)
            val channel = response.bodyAsChannel()
            val totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            var downloadedBytes = 0L
            val bufferSize = 8192
            var lastEmitTime = 0L

            if (totalBytes > maxDownloadBytes) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Archivo demasiado grande para descargar (máx 250 MB)."))
                return@flow
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(bufferSize)
                while (coroutineContext.isActive && activeDownloads[song.id] == true) {
                    val bytesRead = channel.readAvailable(buffer, 0, bufferSize)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (downloadedBytes > maxDownloadBytes) {
                        outputStream.close()
                        outputFile.delete()
                        emit(DownloadResult(song.id, DownloadStatus.FAILED,
                            error = "Archivo demasiado grande para descargar (máx 250 MB)."))
                        return@flow
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 300) {
                        lastEmitTime = now
                        if (totalBytes > 0) {
                            val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                            emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, progress))
                        } else {
                            emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, -1f))
                        }
                    }
                }
            }

            if (activeDownloads[song.id] != true) {
                outputFile.delete()
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = CANCELLED_ERROR))
            } else if (downloadedBytes == 0L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Invidious devolvió contenido vacío"))
                outputFile.delete()
            } else {
                // Embed metadata (title, artist, cover art) into the M4A file
                val highResThumbnail = ThumbnailQualityResolver.resolveBestThumbnailUrl(
                    videoId = song.id,
                    originalUrl = song.thumbnailUrl
                )
                M4aMetadataWriter.writeMetadata(
                    filePath = outputFile.absolutePath,
                    title = song.title,
                    artist = song.artist,
                    thumbnailUrl = highResThumbnail.takeIf { it.isNotBlank() }
                )

                emit(DownloadResult(
                    songId = song.id,
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    outputPath = outputFile.absolutePath
                ))
            }
        } catch (e: Exception) {
            // No dejar parciales truncados en disco (p. ej. corte de red a mitad).
            outputFile.delete()
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = e.message))
        } finally {
            activeDownloads.remove(song.id)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(songId: String) {
        activeDownloads[songId] = false
    }

    private fun invalidateInstance() {
        activeUrl = null
        instanceVerified = false
    }

    private suspend fun resolveInstance(): String {
        if (instanceVerified && activeUrl != null) return activeUrl!!

        // A user-provided instance always wins; otherwise fall back to the
        // verified default. Probing long dead instance lists serially added
        // minutes of latency to every failed search.
        val candidates = buildList {
            RemoteConfig.invidiousUrl?.let { add(it) }
            defaultInstances.forEach { if (it !in this) add(it) }
        }

        for (url in candidates) {
            if (testInstance(url)) {
                activeUrl = url
                instanceVerified = true
                return url
            }
        }

        invalidateInstance()
        val configured = RemoteConfig.invidiousUrl
        throw RuntimeException(
            if (configured != null)
                "No se pudo conectar con la instancia de Invidious configurada ($configured)."
            else
                "No hay instancias de Invidious disponibles. Añade una en ⚙ Ajustes."
        )
    }

    private suspend fun testInstance(url: String): Boolean {
        return try {
            android.util.Log.d("InvidiousApi", "testInstance: $url")
            val resp = httpClient.get("$url/api/v1/search") {
                url { parameters.append("q", "a") }
            }
            if (resp.status.value !in 200..299) {
                android.util.Log.w("InvidiousApi", "testInstance: $url returned status ${resp.status.value}")
                return false
            }
            val body = resp.bodyAsText()
            if (isDeadResponse(body)) {
                android.util.Log.w("InvidiousApi", "testInstance: $url returned dead response")
                return false
            }
            android.util.Log.d("InvidiousApi", "testInstance: $url OK")
            true
        } catch (e: Exception) {
            android.util.Log.w("InvidiousApi", "testInstance: $url failed: ${e.message}")
            false
        }
    }

    /** Returns true if the response body indicates a dead/blocked instance */
    private fun isDeadResponse(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("has shutdown") ||
               lower.contains("has shut down") ||
               lower.contains("invidious has shutdown") ||
               lower.contains("companion is not available") ||
               lower.contains("invidious companion is not available") ||
               lower.contains("captcha") ||
               lower.trimStart().startsWith("<") ||
               (lower.contains("error") && !lower.contains("\"items\"") && body.length < 200)
    }
}
