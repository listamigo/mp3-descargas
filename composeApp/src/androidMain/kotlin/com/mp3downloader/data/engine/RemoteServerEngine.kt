package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.isSafeHttpsUrl
import com.mp3downloader.domain.service.isValidYouTubeId
import com.mp3downloader.domain.service.sanitizeFileName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

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

/**
 * @param baseUrl host this instance talks to. One engine is built per host so
 *   the chain can fall through from Railway to Render. When null it follows
 *   [RemoteConfig.serverUrl], which is what a single-server setup wants.
 */
class RemoteServerEngine(private val baseUrl: String? = null) : DownloadEngine {

    /**
     * A name that says which host this is, so the fallback log lines point at
     * the backend that actually failed instead of printing the same
     * "RemoteServerEngine" twice.
     */
    val label: String = baseUrl
        ?.substringAfter("://")
        ?.substringBefore("/")
        ?: "Remoto"

    /**
     * Resolved per call rather than cached: a Settings change has to reach the
     * engines without restarting the app.
     */
    private fun server(): String? =
        baseUrl ?: RemoteConfig.serverUrl

    /** Reject absurdly large responses to avoid filling the device storage. */
    private val maxDownloadBytes = 250L * 1024 * 1024

    private val httpClient = HttpClient {
        install(HttpTimeout) {
            // Render free tier can take 30-60s for cold start
            // Aumentado para mixes largos: 10 min para request, 10 min para socket
            requestTimeoutMillis = 600_000
            connectTimeoutMillis = 120_000
            socketTimeoutMillis = 600_000
        }
    }

    private val activeDownloads = mutableMapOf<String, Boolean>()

    // Las etiquetas se escriben después de marcar la descarga como completada, así
    // que necesitan un scope propio: si colgaran del flow, la descarga se quedaría
    // "terminada" en la UI sin terminar nunca. SupervisorJob evita que un fallo al
    // incrustar tumbe nada, y Dispatchers.IO es el hilo adecuado (red + disco).
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        val server = server() ?: return Result.failure(RuntimeException(
            "Sin servidor propio configurado (opcional)."
        ))
        return runCatching {
            val raw = httpClient.get("$server/api/search") {
                parameter("q", query)
                if (offset > 0) parameter("offset", offset)
            }.bodyAsText()
            val items = remoteJson.decodeFromString<List<RemoteSearchItem>>(raw)
            items.mapNotNull { item ->
                if (!isValidYouTubeId(item.id)) return@mapNotNull null
                val thumbnailUrl = if (!item.thumbnailUrl.isNullOrBlank() &&
                    isSafeHttpsUrl(item.thumbnailUrl)
                ) {
                    item.thumbnailUrl
                } else {
                    "https://i.ytimg.com/vi/${item.id}/default.jpg"
                }
                Song(
                    id = item.id,
                    title = item.title,
                    artist = item.artist,
                    duration = item.duration.toLong(),
                    thumbnailUrl = thumbnailUrl,
                    audioUrl = item.audioUrl
                )
            }
        }
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        val server = server() ?: return Result.failure(RuntimeException(
            "Sin servidor propio configurado (opcional)."
        ))
        if (!isValidYouTubeId(song.id)) {
            return Result.failure(RuntimeException("ID de video inválido."))
        }
        val videoId = java.net.URLEncoder.encode(song.id, "UTF-8")
        val title = java.net.URLEncoder.encode(song.title, "UTF-8")
        return Result.success("$server/api/preview?videoId=$videoId&title=$title")
    }

    override fun download(
        song: Song,
        outputDir: String
    ): Flow<DownloadResult> = flow {
        val server = server()
            ?: run {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Sin servidor propio configurado (opcional)."))
                return@flow
            }

        emit(DownloadResult(song.id, DownloadStatus.DOWNLOADING, 0f))

        if (!isValidYouTubeId(song.id)) {
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "ID de video inválido."))
            return@flow
        }

        val safeTitle = sanitizeFileName(song.title)
        val outputFile = File(outputDir, "$safeTitle.mp3")

        try {
            activeDownloads[song.id] = true

            val videoId = java.net.URLEncoder.encode(song.id, "UTF-8")
            val audioUrl = java.net.URL("$server/api/download?videoId=$videoId&title=${java.net.URLEncoder.encode(song.title, "UTF-8")}")
            val connection = audioUrl.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 120_000
            connection.readTimeout = 600_000
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            connection.instanceFollowRedirects = true

            val statusCode = connection.responseCode
            if (statusCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                // El host va en el mensaje porque ahora hay dos motores iguales en
                // la cadena: sin él, "Error HTTP 502" no dice si fue Railway o
                // Render, que es justo lo que hace falta para diagnosticar.
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = if (errorBody.length in 10..500) "$label: $errorBody" else "$label: Error HTTP $statusCode"))
                return@flow
            }

            val inputStream = connection.inputStream
            val totalBytes = connection.contentLengthLong
            if (totalBytes > maxDownloadBytes) {
                inputStream.close()
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Archivo demasiado grande para descargar (máx 250 MB)."))
                return@flow
            }
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

                    if (downloadedBytes > maxDownloadBytes) {
                        inputStream.close()
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

            inputStream.close()

            if (activeDownloads[song.id] != true) {
                outputFile.delete()
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = CANCELLED_ERROR))
            } else if (downloadedBytes == 0L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "El servidor devolvió contenido vacío"))
                outputFile.delete()
            } else if (downloadedBytes < 1024L) {
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Error: archivo demasiado pequeño ($downloadedBytes bytes). Puede que YouTube haya bloqueado la descarga."))
                outputFile.delete()
            } else {
                // El audio ya está completo y es reproducible en disco, así que se
                // marca COMPLETED antes de escribir las etiquetas. Incrustar la
                // portada exige una descarga de red encadenada (hasta 5 URLs, cada
                // una con su timeout) más la reescritura completa del fichero, y
                // hacerlo aquí congelaba la UI con la descarga al 100% durante
                // decenas de segundos. Se hace en segundo plano: el fichero es
                // válido sin etiquetas y estas aterrizan un instante después.
                emit(DownloadResult(
                    songId = song.id,
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    outputPath = outputFile.absolutePath
                ))

                val metadataThumb = song.thumbnailUrl
                    .takeIf { it.isNotBlank() && isSafeHttpsUrl(it) }
                val targetFile = outputFile.absolutePath
                val metaTitle = song.title
                val metaArtist = song.artist
                metadataScope.launch {
                    try {
                        com.mp3downloader.domain.service.M4aMetadataWriter.writeMetadata(
                            filePath = targetFile,
                            title = metaTitle,
                            artist = metaArtist,
                            thumbnailUrl = metadataThumb
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("RemoteServerEngine", "Metadata embedding failed: ${e.message}", e)
                    }
                }
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

    suspend fun checkHealth(): Boolean {
        val server = server() ?: return false
        return try {
            val resp = httpClient.get("$server/api/health")
            resp.status.value in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
