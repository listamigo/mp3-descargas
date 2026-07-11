package com.mp3downloader.data.engine

import com.mp3downloader.data.dto.InvidiousSearchItem
import com.mp3downloader.data.dto.InvidiousVideoResponse
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.M4aMetadataWriter
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
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

object InvidiousConfig {
    var customInstanceUrl: String? = null
}

class InvidiousApiEngine : DownloadEngine {

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

    private val defaultFallbacks = listOf(
        "https://inv.zoomerville.com",
        "https://invidious.slipfox.xyz",
        "https://invidious.projectsegfau.lt",
        "https://invidious.protokolla.fi",
        "https://invidious.flokinet.to",
        "https://vid.puffyan.us",
        "https://iv.ggtyler.dev",
    )

    private var activeUrl: String? = null
    private var instanceVerified = false
    private val activeDownloads = mutableMapOf<String, Boolean>()

    override suspend fun search(query: String): Result<List<Song>> {
        return runCatching {
            android.util.Log.d("InvidiousApi", "search: starting query=$query")
            val url = resolveInstance()
            android.util.Log.d("InvidiousApi", "search: using instance=$url")
            val raw = httpClient
                .get("$url/api/v1/search") {
                    url { parameters.append("q", query) }
                }
                .bodyAsText()
            android.util.Log.d("InvidiousApi", "search: response length=${raw.length}")
            val items = json.decodeFromString<List<InvidiousSearchItem>>(raw)
            val songs = items.mapNotNull { item ->
                if (item.videoId.isBlank()) return@mapNotNull null
                Song(
                    id = item.videoId,
                    title = item.title,
                    artist = item.author ?: "Unknown",
                    duration = (item.lengthSeconds ?: 0L),
                    thumbnailUrl = item.videoThumbnails?.firstOrNull { it.quality == "medium" }?.url
                        ?: "https://i.ytimg.com/vi/${item.videoId}/default.jpg",
                    audioUrl = null
                )
            }
            android.util.Log.d("InvidiousApi", "search: ${songs.size} results")
            songs
        }
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        return runCatching {
            val url = resolveInstance()
            val raw = httpClient
                .get("$url/api/v1/videos/${song.id}")
                .bodyAsText()

            if (raw.trimStart().startsWith("<")) {
                val preview = raw.take(100).replace("\n", " ")
                throw RuntimeException("Invidious ($url) devolvió HTML (bloqueado). Respuesta: $preview")
            }

            val video = json.decodeFromString<InvidiousVideoResponse>(raw)

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

            bestAudio.url
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
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Invidious devolvió contenido vacío"))
                outputFile.delete()
            } else {
                // Embed metadata (title, artist, cover art) into the M4A file
                M4aMetadataWriter.writeMetadata(
                    filePath = outputFile.absolutePath,
                    title = song.title,
                    artist = song.artist,
                    thumbnailUrl = song.thumbnailUrl.takeIf { it.isNotBlank() }
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
        val custom = InvidiousConfig.customInstanceUrl
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
        android.util.Log.e("InvidiousApi", "resolveInstance: all ${defaultFallbacks.size} instances failed")
        throw RuntimeException("No se pudo conectar a ningún servidor de Invidious. " +
            "Configura tu propio servidor en ⚙ Ajustes > Servidor.")
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
