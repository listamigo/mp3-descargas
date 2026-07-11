package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.flow.Flow

data class DownloadResult(
    val songId: String,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val error: String? = null
)

/** Number of results requested per search page. */
const val SEARCH_PAGE_SIZE: Int = 20

interface DownloadEngine {
    suspend fun search(query: String, offset: Int = 0): Result<List<Song>>
    suspend fun getAudioStreamUrl(song: Song): Result<String>
    fun download(song: Song, outputDir: String): Flow<DownloadResult>
    suspend fun cancel(songId: String)
}
