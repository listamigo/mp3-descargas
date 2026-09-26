package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.flow.Flow

data class DownloadResult(
    val songId: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val error: String? = null,
    /**
     * Bytes written so far. The UI shows this because the remote streams the
     * audio without a Content-Length, so the bar alone does not say how much is
     * left.
     */
    val downloadedBytes: Long = 0L,
    /** Current throughput, for the "at 320 KB/s" part of the progress line. */
    val bytesPerSecond: Long = 0L,
    /**
     * Altura REAL del MP4 servido, leída de la cabecera `X-Video-Height`.
     * 0 cuando el servidor no la manda o cuando es audio.
     *
     * Existe por una razón concreta: pedir 1080p y recibir 360p era el
     * defecto de esta ruta. El selector cae a su último término cuando no
     * hay formatos altos y no dice nada, así que la app anunciaba 1080p
     * sobre un fichero de 640x360. Con la medida, la etiqueta dice lo que
     * hay en el disco.
     */
    val deliveredHeight: Int = 0
)

/** Number of results requested per search page. */
const val SEARCH_PAGE_SIZE: Int = 20

/**
 * Error message used by every engine to signal that the user cancelled the
 * download. FallbackEngine relies on it to stop the fallback chain instead of
 * trying the remaining engines, and the ViewModel uses it to avoid showing a
 * "download failed" message for a deliberate cancellation.
 */
const val CANCELLED_ERROR: String = "Cancelado"

/**
 * Qué se descarga de un resultado.
 *
 * AUDIO es el MP3 de 256 kbps que el servidor produce siempre. VIDEO pide el
 * MP4 mergeado, que solo saben hacer los motores remotos: Invidious y Piped
 * siguen siendo de audio, y por eso declaran [DownloadEngine.supportsVideo].
 */
enum class MediaKind { AUDIO, VIDEO }

/**
 * Calidades de vídeo que el selector ofrece.
 *
 * Fijas y no "la mejor disponible": con "best" el tamaño final depende de lo
 * que YouTube tenga publicado en ese momento, y el cliente necesita un total
 * fiable para que el porcentaje signifique algo. 1080p es el techo: por encima
 * YouTube solo publica VP9/AV1 y el merge a MP4 dejaría de ser una copia de
 * pistas. Del peso se encargan los dos lados: el servidor rechaza antes de
 * descargar si no cabe, y el cliente comprueba el total al recibirlo.
 */
val VIDEO_QUALITIES: List<Int> = listOf(240, 360, 480, 720, 1080)

interface DownloadEngine {
    suspend fun search(query: String, offset: Int = 0): Result<List<Song>>
    suspend fun getAudioStreamUrl(song: Song): Result<String>
    fun download(
        song: Song,
        outputDir: String,
        media: MediaKind = MediaKind.AUDIO,
        quality: Int = 0
    ): Flow<DownloadResult>
    suspend fun cancel(songId: String)

    /**
     * False when this engine can only ever produce audio. The chain uses it to
     * refuse a video download up front rather than quietly handing back an MP3
     * for a request that asked for an MP4.
     */
    fun supportsVideo(): Boolean = false

    /**
     * True while this engine's circuit breaker says the backend is down, used
     * by the chain to try it last. Only the remote engines have a host that can
     * fail on its own, so the default is false and the others need not know.
     */
    fun isTripped(): Boolean = false
}
