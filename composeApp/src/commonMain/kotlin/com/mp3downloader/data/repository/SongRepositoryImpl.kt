package com.mp3downloader.data.repository

import com.mp3downloader.data.engine.DownloadEngine
import com.mp3downloader.data.engine.DownloadResult
import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import com.mp3downloader.domain.service.LruCache

/**
 * Bounded, TTL-aware in-memory cache for search pages. Avoids hammering the
 * backend with identical queries and makes re-searching instant.
 */
private class SearchCache(
    private val maxEntries: Int = 100,
    private val ttlMillis: Long = 10 * 60 * 1000
) {
    private data class Entry(val songs: List<Song>, val ts: Long)

    private val store = LruCache<String, Entry>(maxEntries)

    suspend fun get(key: String): List<Song>? {
        val entry = store.get(key) ?: return null
        if (System.currentTimeMillis() - entry.ts > ttlMillis) {
            store.remove(key)
            return null
        }
        return entry.songs
    }

    suspend fun put(key: String, songs: List<Song>) {
        store.put(key, Entry(songs, System.currentTimeMillis()))
    }
}

class SongRepositoryImpl(
    private val engine: DownloadEngine
) : SongRepository {

    private val cache = SearchCache()

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        val key = "$query#$offset"
        cache.get(key)?.let { return Result.success(it) }
        return engine.search(query, offset).also { result ->
            if (result.isSuccess) cache.put(key, result.getOrDefault(emptyList()))
        }
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
