package com.mp3downloader.data.engine


import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.isSafeHttpsUrl
import com.mp3downloader.domain.service.isValidYouTubeId
import com.mp3downloader.domain.service.sanitizeFileName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
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

/** Respuesta de /api/ready: si el host puede servir ese vídeo ahora. */
@Serializable
data class RemoteReadyResponse(
    val ok: Boolean = false,
    val estimatedBytes: Long? = null,
    val limitBytes: Long? = null
)

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
 * @param host host this instance talks to, resolved on **every** call instead of
 *   being captured at construction time. Koin builds the engine chain once per
 *   process, so a URL frozen in the constructor would keep pointing at the old
 *   host until the app was restarted, and a server changed in Settings would
 *   look like it had not been saved. One engine per role, so the chain can fall
 *   through from Railway to Render.
 */
class RemoteServerEngine(private val host: () -> String? = { RemoteConfig.serverUrl }) : DownloadEngine {

    /**
     * A name that says which host this is, so the fallback log lines point at the
     * backend that actually failed instead of printing the same
     * "RemoteServerEngine" twice. Also a getter for the same reason as [host].
     */
    val label: String
        get() = server()?.substringAfter("://")?.substringBefore("/") ?: "Remoto"

    /**
     * Resolved per call rather than cached: a Settings change has to reach the
     * engines without restarting the app.
     */
    private fun server(): String? = host()

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
     * Ceiling for video: 1 GB, the same number the server enforces.
     *
     * They have to agree. The server checks the estimated size before it
     * downloads anything, and checks the real size again after the merge, so a
     * video that cannot fit is refused in seconds instead of after the host
     * has spent minutes and disk on it. This is the last line of defence for
     * the device itself, and the number is duplicated there on purpose: if the
     * two ever disagree the server rejects the transfer and this never sees it.
     */
    private val maxVideoBytes = 1024L * 1024 * 1024

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

    /**
     * true  = este host sirve el vídeo ahora mismo.
     * false = ahora mismo no puede (YouTube lo rechaza) y no tiene sentido
     *         esperar 40-70 s a un 502 para descubrirlo.
     * null  = no se pudo saber. Se devuelve null, y no false, cuando la
     *         pregunta falla por lo que sea: un host viejo sin el endpoint, una
     *         red inestable. Ante la duda se descarga, porque un falso "no"
     *         rompería descargas que sí habrían salido.
     */
    private suspend fun probeReady(videoId: String, quality: Int): Boolean? {
        val server = server() ?: return null
        return try {
            val raw = httpClient.get("$server/api/ready") {
                parameter("videoId", videoId)
                parameter("quality", quality)
                // El cliente compartido espera 10 min porque una descarga larga
                // lo necesita; esta pregunta no. Si el host tarda más de 25 s en
                // responder, es que tampoco va a servir el vídeo.
                timeout { requestTimeoutMillis = 25_000 }
            }.bodyAsText()
            if (remoteJson.decodeFromString<RemoteReadyResponse>(raw).ok) {
                reportSuccess()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            // Un 404 de un servidor que no conoce el endpoint, un timeout o
            // cualquier otra cosa: no sabemos, no bloqueamos.
            null
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

        val isVideo = media == MediaKind.VIDEO

        // El MP4 no existe en el servidor hasta que yt-dlp lo ha descargado y
        // ffmpeg lo ha fusionado, y las cabeceras no salen hasta entonces: la
        // respuesta empieza cuando el fichero ya está listo. Anunciar
        // "Descargando 0%" durante esa espera parece una descarga colgada, así
        // que el estado dice "preparando" y solo pasa a "descargando" cuando
        // empiezan a llegar bytes. El MP3 no se toca: su respuesta es un stream
        // en vivo y siempre se vio así.
        emit(
            DownloadResult(
                songId = song.id,
                status = if (isVideo) DownloadStatus.CONVERTING else DownloadStatus.DOWNLOADING,
                progress = 0f
            )
        )

        if (!isValidYouTubeId(song.id)) {
            emit(DownloadResult(song.id, DownloadStatus.FAILED, error = "ID de video inválido."))
            return@flow
        }

        val safeTitle = sanitizeFileName(song.title)
        val outputFile = File(outputDir, "$safeTitle.${if (isVideo) "mp4" else "mp3"}")
        // Un MP4 pesa bastante más que el MP3 del mismo vídeo: un 1080p de
        // tres minutos ronda los 40-60 MB y una peli larga pasa de 1 GB. El
        // tope de audio (250 MB) rechazaría casi todo lo que se pida como
        // vídeo, así que el guard usa un límite propio.
        val sizeLimit = if (isVideo) maxVideoBytes else maxDownloadBytes

        // Preguntar antes de descargar si este host puede sacar este vídeo. Sin
        // esto, un host bloqueado por YouTube tarda 40-70 s en contestar 502, y
        // con dos hosts en la cadena el usuario espera más de 3 minutos sin
        // saber qué pasa. La pregunta no es un trasto: el servidor la resuelve
        // con la misma extracción de metadatos que necesita para saber el peso
        // del MP4, así que la descarga después no la repite.
        //
        // Solo en vídeo: en audio la respuesta es un stream en vivo y no hay
        // nada que preparar. Un null significa "no se pudo saber" (la pregunta
        // falló, el host es viejo y no tiene el endpoint) y en ese caso se
        // sigue con la descarga de siempre en vez de inventarse un fallo.
        if (isVideo) {
            val ready = probeReady(song.id, quality)
            if (ready == false) {
                val detail = "el servidor no puede acceder a YouTube ahora mismo"
                reportFailure(detail)
                emit(DownloadResult(song.id, DownloadStatus.FAILED, error = detail))
                return@flow
            }
        }

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
            // El servidor mide el MP4 con ffprobe antes de servirlo y manda la
            // altura real. Es la unica forma de que la etiqueta diga 360p si
            // lo que hay en el disco es 360p, en vez de prometer lo pedido.
            val deliveredHeight = connection.getHeaderField("X-Video-Height")
                ?.trim()?.toIntOrNull() ?: 0
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

            // Cabeceras recibidas: el fichero ya está preparado y empieza a
            // transferred. Sin Content-Length no hay porcentaje honesto que
            // enseñar, así que se pasa a la barra indeterminada en vez de un 0 %
            // que no se mueve nunca.
            emit(
                DownloadResult(
                    songId = song.id,
                    status = DownloadStatus.DOWNLOADING,
                    progress = if (totalBytes > 0) 0f else -1f,
                    downloadedBytes = 0L,
                    bytesPerSecond = 0L
                )
            )

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
                    outputPath = outputFile.absolutePath,
                    deliveredHeight = deliveredHeight
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
