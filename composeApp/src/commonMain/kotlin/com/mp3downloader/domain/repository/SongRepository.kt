package com.mp3downloader.domain.repository

import com.mp3downloader.data.engine.DownloadResult
import com.mp3downloader.domain.model.DownloadTask
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface SongRepository {
    suspend fun search(query: String): Result<List<Song>>
    suspend fun getAudioUrl(song: Song): Result<String>
    fun download(song: Song, outputDir: String): Flow<DownloadResult>
    suspend fun cancelDownload(songId: String)
}
