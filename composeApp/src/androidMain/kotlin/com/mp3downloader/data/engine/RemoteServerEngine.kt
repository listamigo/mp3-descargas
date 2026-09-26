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

    /**
     * Tripped by the engine when a request to this host fails outright, read
     * before spending a request on it. See [HostBreaker].
     */
    fun reportSuccess() = HostBreaker.reportSuccess(server())

    fun reportFailure(detail: String) = HostBreaker.reportFailure(server(), detail)

    override fun isTripped(): Boolean = HostBreaker.isTripped(server())

    /** Este motor es el que sabe pedir `?mode=video&quality=` al servidor. */
    override fun supportsVideo(): Boolean = true

    /** Reject absurdly large responses to avoid filling the device storage. */
    private val maxDownloadBytes = 250L * 1024 * 1024

    /**
     * Ceiling for video: double the audio one, 500 MB. That is roughly forty
     * minutes of 1080p, and past it the transfer takes longer than anyone
     * waits, so refusing up front beats filling the device.
     */
    private val maxVideoBytes = 500L * 1024 * 1024

    /**
     * Bytes per second of the encoded MP3, used to turn a duration into a size.
     *
     * The server streams the audio as ffmpeg produces it, so it cannot know the
     * final length and sends no Content-Length. But it encodes with
     * `-codec:a libmp3lame -b:a 256k`, and CBR MP3 is exactly 32 bytes per 1 ms,
     * so the total is predictable: measured against a real Render download of
     * 355,7 s / 11.382.837 bytes, the figure holds to 0,001 %. That is accurate
     * enough to drive a progress bar, and unlike a declared Content-Length it
     * cannot desync the response framing if the real size differs.
     */
    private val expectedBytesPerSecond = 32_000L

    /**
     * Best guess at the total size, from the duration the search returned.
     *
     * It is the video duration, not the audio duration, so for a video whose
     * audio is shorter this overshoots and the bar lags instead of lying. That
     * is the safe direction: [maxProgressWhileStreaming] stops the bar from
     * reaching 100% before the stream really ends, and the completion event
     * sets the final value.
     */
    private fun expectedTotalBytes(song: Song): Long {
        if (song.duration <= 0L) return 0L
        // Cap so a bogus duration cannot make a limit that trips the size guard.
        val estimate = song.duration * expectedBytesPerSecond
        return estimate.coerceIn(0L, maxDownloadBytes)
    }

    /** Never show a full bar while there is still data arriving. */
    private val maxProgressWhileStreaming = 0.99f

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
        val result = runCatching {
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
        if (result.isSuccess) reportSuccess()
        else reportFailure(result.exceptionOrNull()?.message ?: "error desconocido")
        return result
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
        outputDir: String,
        media: MediaKind,
        quality: Int
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

        val isVideo = media == MediaKind.VIDEO
        val safeTitle = sanitizeFileName(song.title)
        val outputFile = File(outputDir, "$safeTitle.${if (isVideo) "mp4" else "mp3"}")
        // Un MP4 pesa bastante más que el MP3 del mismo vídeo: un 1080p de
        // tres minutos ronda los 40-60 MB, y una hora Easily pasa de 500 MB.
        // El tope de audio (250 MB) rechazaría casi todo lo que se pida como
        // vídeo, así que el guard usa un límite propio.
        val sizeLimit = if (isVideo) maxVideoBytes else maxDownloadBytes

        try {
            activeDownloads[song.id] = true

            val videoId = java.net.URLEncoder.encode(song.id, "UTF-8")
            // mode/quality solo se mandan en vídeo; en audio la URL queda igual
            // que siempre, de modo que un servidor viejo que no conozca los
            // parámetros sigue sirviendo MP3 sin cambios.
            val mediaParams = if (isVideo) "&mode=video&quality=$quality" else ""
            val audioUrl = java.net.URL("$server/api/download?videoId=$videoId&title=${java.net.URLEncoder.encode(song.title, "UTF-8")}$mediaParams")
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
                reportFailure("HTTP $statusCode")
                return@flow
            }

            val inputStream = connection.inputStream
            val headerBytes = connection.contentLengthLong
            // The server only sets Content-Length on a cache hit; on a cache
            // miss it streams chunked, so fall back to the estimate.
            //
            // The estimate is an MP3 constant, so it must not touch a video: the
            // server merges the MP4 to a file before serving it and always sends
            // its real length, and if that header is missing for a video we
            // honestly show bytes and speed with no percentage rather than a
            // bar built on a bitrate that has nothing to do with the file.
            val totalBytes = when {
                headerBytes > 0 -> headerBytes
                isVideo -> 0L
                else -> expectedTotalBytes(song)
            }
            if (totalBytes > sizeLimit) {
                inputStream.close()
                emit(DownloadResult(song.id, DownloadStatus.FAILED,
                    error = "Archivo demasiado grande para descargar (máx ${sizeLimit / 1048576} MB)."))
                return@flow
            }
            var downloadedBytes = 0L
            val bufferSize = 8192
            var lastEmitTime = 0L
            val startedAt = System.currentTimeMillis()

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(bufferSize)
                while (coroutineContext.isActive && activeDownloads[song.id] == true) {
                    val bytesRead = inputStream.read(buffer, 0, bufferSize)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (downloadedBytes > sizeLimit) {
                        inputStream.close()
                        outputFile.delete()
                        emit(DownloadResult(song.id, DownloadStatus.FAILED,
                            error = "Archivo demasiado grande para descargar (máx 250 MB)."))
                        return@flow
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= 300) {
                        // First tick counts from the start of the transfer, not
                        // from the first emit, otherwise the speed reads low.
                        val elapsedSec = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                        val speed = (downloadedBytes / elapsedSec).toLong()
                        lastEmitTime = now
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat())
                                .coerceIn(0f, maxProgressWhileStreaming)
                            emit(DownloadResult(
                                songId = song.id,
                                status = DownloadStatus.DOWNLOADING,
                                progress = progress,
                                downloadedBytes = downloadedBytes,
                                bytesPerSecond = speed
                            ))
                        } else {
                            // No duration to estimate from: bytes and speed are
                            // all we can honestly show.
                            emit(DownloadResult(
                                songId = song.id,
                                status = DownloadStatus.DOWNLOADING,
                                progress = -1f,
                                downloadedBytes = downloadedBytes,
                                bytesPerSecond = speed
                            ))
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
                reportSuccess()

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
            // Una cancelación no dice nada del host: el corte lo pidió el
            // usuario, así que no debe abrir el cortacircuitos.
            if (e.message != CANCELLED_ERROR) reportFailure(e.message ?: "excepcion")
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
