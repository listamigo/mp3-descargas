package com.mp3downloader.data.engine

import com.mp3downloader.data.dto.PipedSearchResponse
import com.mp3downloader.data.dto.PipedStreamResponse
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.M4aMetadataWriter
import com.mp3downloader.domain.service.ThumbnailQualityResolver
import com.mp3downloader.domain.service.isSafeHttpsUrl
import com.mp3downloader.domain.service.isValidYouTubeId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

object PipedConfig {
    var customInstanceUrl: String? = null
}

class PipedApiEngine : DownloadEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {
        install(HttpRedirect)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 8_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
        }
    }

    private val defaultFallbacks = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.syncpundit.io",
        "https://api-piped.mha.fi",
        "https://pipedapi.r4fo.com",
        "https://pipedapi.frontendfriendly.xyz",
    )

    private var activeUrl: String? = null
    private var instanceVerified = false
    private val activeDownloads = mutableMapOf<String, Boolean>()

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        return runCatching {
            val url = resolveInstance()
            val page = (offset / SEARCH_PAGE_SIZE) + 1
            val raw = httpClient
                .get("$url/search") {
                    url {
                        parameters.append("q", query)
                        parameters.append("filter", "videos")
                        parameters.append("page", page.toString())
                    }
                }
                .bodyAsText()

            // Detect shutdown/dead instance responses
            checkShutdown(raw, url)

            val parsed = json.decodeFromString<PipedSearchResponse>(raw)
            parsed.items.mapNotNull { item ->
                val id = extractVideoId(item.url)
                if (!isValidYouTubeId(id)) return@mapNotNull null
                Song(
                    id = id,
                    title = item.title,
                    artist = item.uploaderName ?: "Unknown",
                    duration = (item.duration ?: 0L),
                    thumbnailUrl = item.thumbnail ?: "",
                    audioUrl = null
                )
            }
        }
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        return runCatching {
            val url = resolveInstance()
            val raw = httpClient
                .get("$url/streams/${song.id}")
                .bodyAsText()

            // Detect shutdown/dead instance responses
            checkShutdown(raw, url)

            val hasError = try {
                json.decodeFromString<kotlinx.serialization.json.JsonObject>(raw)["error"] != null
            } catch (_: Exception) { false }

            if (hasError) {
                throw RuntimeException("YouTube bloqueó el acceso en $url. Configura el servidor de escritorio en \u2699 Ajustes.")
            }

            val streamResponse = json.decodeFromString<PipedStreamResponse>(raw)

            val bestAudio = streamResponse.audioStreams
                .filter { it.mimeType?.contains("mp4") == true || it.mimeType?.contains("m4a") == true }
                .maxByOrNull { it.bitRate ?: 0 }
                ?: streamResponse.audioStreams.firstOrNull()

            if (bestAudio != null) {
                if (!isSafeHttpsUrl(bestAudio.url)) {
                    throw RuntimeException("URL de audio insegura recibida de Piped")
                }
                return@runCatching bestAudio.url
            }

            // If no dedicated audio stream, try video streams (some have combined audio)
            val bestVideo = streamResponse.videoStreams
                .filter { it.mimeType?.contains("mp4") == true }
                .maxByOrNull {
                    val q = it.quality ?: ""
                    when {
                        q.contains("360") -> 360
                        q.contains("480") -> 480
                        q.contains("720") -> 720
                        q.contains("1080") -> 1080
                        else -> 0
                    }
                }

            if (bestVideo != null) {
                if (!isSafeHttpsUrl(bestVideo.url)) {
                    throw RuntimeException("URL de audio insegura recibida de Piped")
                }
                return@runCatching bestVideo.url
            }

            val preview = raw.take(200).replace("\n", " ")
            throw RuntimeException("Sin streams en $url. Respuesta: $preview")
        }
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

        try {
            val safeTitle = song.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val outputFile = uniqueOutputFile(outputDir, safeTitle, "m4a")
            activeDownloads[song.id] = true

            val response: HttpResponse = httpClient.get(audioUrl)
            val channel = response.bodyAsChannel()
            val totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            var downloadedBytes = 0L
            val bufferSize = 8192
            var lastEmitTime = 0L

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(bufferSize)
                while (coroutineContext.isActive && activeDownloads[song.id] == true) {
                    val bytesRead = channel.readAvailable(buffer, 0, bufferSize)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

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
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Cancelled"))
            } else if (downloadedBytes == 0L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Servidor devolvió contenido vacío"))
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
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = e.message))
        } finally {
            activeDownloads.remove(song.id)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(songId: String) {
        activeDownloads[songId] = false
    }

    private suspend fun resolveInstance(): String {
        if (instanceVerified && activeUrl != null) return activeUrl!!
        val custom = PipedConfig.customInstanceUrl
        if (custom != null && testInstance(custom)) {
            activeUrl = custom
            instanceVerified = true
            return custom
        }
        if (activeUrl != null && testInstance(activeUrl!!)) {
            instanceVerified = true
            return activeUrl!!
        }
        for (url in defaultFallbacks) {
            if (url == activeUrl) continue
            if (testInstance(url)) {
                activeUrl = url
                instanceVerified = true
                return url
            }
        }
        throw RuntimeException(
            if (custom != null)
                "Error de conexi\u00F3n con $custom. Verifica la URL o intenta con otra."
            else
                "No hay servidores Piped disponibles. Usa la aplicaci\u00F3n de PC para descargar."
        )
    }

    private suspend fun testInstance(url: String): Boolean {
        return try {
            val resp = httpClient.get("$url/streams/dQw4w9WgXcQ")
            if (resp.status.value !in 200..299) return false
            // Check response body for shutdown/error markers
            val body = resp.bodyAsText()
            if (hasShutdownMarker(body)) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Check if response indicates the instance has shut down */
    private fun checkShutdown(body: String, url: String) {
        if (hasShutdownMarker(body)) {
            throw RuntimeException(
                "El servicio en $url ha cerrado. " +
                "Configura tu propio servidor en \u2699 Ajustes > Servidor " +
                "(ejecuta la app de PC o despliega el servidor en Render)."
            )
        }
    }

    private fun hasShutdownMarker(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("has shutdown") ||
               lower.contains("has shut down") ||
               lower.contains("service has been shutdown") ||
               (lower.contains("shutdown") && lower.length < 80) ||
               lower.startsWith("<!doctype html") ||
               lower.startsWith("<html") ||
               lower.contains("captcha")
    }

    private fun extractVideoId(url: String): String {
        return url.removePrefix("/watch?v=").takeIf { it.length == 11 }
            ?: url.takeLast(11)
    }
}

private fun uniqueOutputFile(outputDir: String, baseName: String, extension: String): File {
    val dir = File(outputDir)
    val base = File(dir, "$baseName.$extension")
    if (!base.exists()) return base
    var n = 1
    while (true) {
        val candidate = File(dir, "$baseName ($n).$extension")
        if (!candidate.exists()) return candidate
        n++
    }
}
