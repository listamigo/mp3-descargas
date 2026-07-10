package com.mp3downloader.data.repository

import com.mp3downloader.data.engine.DownloadEngine
import com.mp3downloader.data.engine.DownloadResult
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow

class SongRepositoryImpl(
    private val engine: DownloadEngine
) : SongRepository {

    override suspend fun search(query: String): Result<List<Song>> {
        return engine.search(query)
    }

    override suspend fun getAudioUrl(song: Song): Result<String> {
        return engine.getAudioStreamUrl(song)
    }

    override fun download(song: Song, outputDir: String): Flow<DownloadResult> {
        return engine.download(song, outputDir)
    }

    override suspend fun cancelDownload(songId: String) {
        engine.cancel(songId)
    }
}
