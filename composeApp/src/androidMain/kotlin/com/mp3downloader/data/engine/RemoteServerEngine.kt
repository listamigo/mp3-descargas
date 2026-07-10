package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

object ServerStatus {
    var isOnline = false
}

@Serializable
data class RemoteSearchItem(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val audioUrl: String? = null
)

private val remoteJson = Json { ignoreUnknownKeys = true }

class RemoteServerEngine : DownloadEngine {

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            // Render free tier can take 30-60s for cold start
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 120_000
        }
    }

    private val activeDownloads = mutableMapOf<String, Boolean>()

    override suspend fun search(query: String): Result<List<Song>> {
        val server = RemoteConfig.serverUrl ?: return Result.failure(RuntimeException(
            "No hay servidor configurado. Ve a Ajustes > Servidor e ingresa la IP de tu PC."
        ))
        return runCatching {
            val raw = httpClient.get("$server/api/search") {
                parameter("q", query)
            }.bodyAsText()
            val items = remoteJson.decodeFromString<List<RemoteSearchItem>>(raw)
            items.map { item ->
                Song(
                    id = item.id,
                    title = item.title,
                    artist = item.artist,
                    duration = item.duration.toLong(),
                    thumbnailUrl = item.thumbnailUrl ?: "https://i.ytimg.com/vi/${item.id}/default.jpg",
                    audioUrl = item.audioUrl
                )
            }
        }
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        val server = RemoteConfig.serverUrl ?: return Result.failure(RuntimeException(
            "No hay servidor configurado."
        ))
        // Verify server is reachable before returning the download URL
        if (!checkHealth()) {
            return Result.failure(RuntimeException(
                "El servidor $server no responde. Verifica la URL o intenta más tarde."
            ))
        }
        return Result.success("$server/api/download?videoId=${song.id}&title=${java.net.URLEncoder.encode(song.title, "UTF-8")}")
    }

    override fun download(
        song: Song,
        outputDir: String
    ): Flow<DownloadResult> = flow {
        val server = RemoteConfig.serverUrl
            ?: run {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "No hay servidor configurado."))
                return@flow
            }

        emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, 0f))

        val safeTitle = song.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val outputFile = File(outputDir, "$safeTitle.m4a")

        try {
            activeDownloads[song.id] = true

            val audioUrl = java.net.URL("$server/api/download?videoId=${song.id}&title=${java.net.URLEncoder.encode(song.title, "UTF-8")}")
            val connection = audioUrl.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 60_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            connection.instanceFollowRedirects = true

            val statusCode = connection.responseCode
            if (statusCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = if (errorBody.length in 10..500) "Servidor: $errorBody" else "Error HTTP $statusCode"))
                return@flow
            }

            val inputStream = connection.inputStream
            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val bufferSize = 8192
            var lastEmitTime = 0L

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(bufferSize)
                while (coroutineContext.isActive && activeDownloads[song.id] == true) {
                    val bytesRead = inputStream.read(buffer, 0, bufferSize)
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

            inputStream.close()

            if (activeDownloads[song.id] != true) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "Cancelled"))
            } else if (downloadedBytes == 0L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "El servidor devolvió contenido vacío"))
                outputFile.delete()
            } else if (downloadedBytes < 1024L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Error: archivo demasiado pequeño ($downloadedBytes bytes). Puede que YouTube haya bloqueado la descarga."))
                outputFile.delete()
            } else {
                // Embed metadata (title, artist, cover art) into the M4A file
                try {
                    com.mp3downloader.domain.service.M4aMetadataWriter.writeMetadata(
                        filePath = outputFile.absolutePath,
                        title = song.title,
                        artist = song.artist,
                        thumbnailUrl = song.thumbnailUrl.takeIf { it.isNotBlank() }
                    )
                } catch (_: Exception) {}

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

    suspend fun checkHealth(): Boolean {
        val server = RemoteConfig.serverUrl ?: return false
        return try {
            val resp = httpClient.get("$server/api/health")
            resp.status.value in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
