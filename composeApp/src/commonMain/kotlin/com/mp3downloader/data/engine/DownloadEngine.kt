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
    val bytesPerSecond: Long = 0L
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

interface DownloadEngine {
    suspend fun search(query: String, offset: Int = 0): Result<List<Song>>
    suspend fun getAudioStreamUrl(song: Song): Result<String>
    fun download(song: Song, outputDir: String): Flow<DownloadResult>
    suspend fun cancel(songId: String)

    /**
     * True while this engine's circuit breaker says the backend is down, used
     * by the chain to try it last. Only the remote engines have a host that can
     * fail on its own, so the default is false and the others need not know.
     */
    fun isTripped(): Boolean = false
}
