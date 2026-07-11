package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FallbackEngine(
    private val engines: List<DownloadEngine>
) : DownloadEngine {

    // Remembers the engine that handled the last successful search so that
    // subsequent "load more" pages come from the same source.
    private var lastSearchEngine: DownloadEngine? = null

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        val errors = mutableListOf<String>()
        // Prefer the engine that already succeeded for the previous page so
        // pagination stays consistent across "load more" requests.
        val ordered = if (lastSearchEngine != null) {
            listOf(lastSearchEngine!!) + engines.filter { it !== lastSearchEngine }
        } else {
            engines
        }
        for ((i, engine) in ordered.withIndex()) {
            android.util.Log.d("FallbackEngine", "search: trying engine[$i]=${engine::class.simpleName} offset=$offset")
            val result = engine.search(query, offset)
            if (result.isSuccess) {
                lastSearchEngine = engine
                android.util.Log.d("FallbackEngine", "search: engine[$i] succeeded")
                return result
            }
            val err = result.exceptionOrNull()?.message ?: "Error desconocido"
            android.util.Log.w("FallbackEngine", "search: engine[$i] failed: $err")
            errors.add(err)
        }
        return Result.failure(RuntimeException(
            "Todos los motores fallaron: ${errors.joinToString("; ")}"
        ))
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        val errors = mutableListOf<String>()
        for (engine in engines) {
            val result = engine.getAudioStreamUrl(song)
            if (result.isSuccess) return result
            errors.add(result.exceptionOrNull()?.message ?: "Error desconocido")
        }
        return Result.failure(RuntimeException(
            "Todos los motores fallaron: ${errors.joinToString("; ")}"
        ))
    }

    override fun download(song: Song, outputDir: String): Flow<DownloadResult> = flow {
        val errors = mutableListOf<String>()
        for (engine in engines) {
            var finished = false
            var succeeded = false
            var errorMsg: String? = null

            try {
                engine.download(song, outputDir).collect { result ->
                    if (result.status == DownloadStatus.COMPLETED) {
                        emit(result)
                        succeeded = true
                        finished = true
                    } else if (result.status == DownloadStatus.FAILED) {
                        finished = true
                        succeeded = false
                        errorMsg = result.error
                    } else {
                        emit(result)
                    }
                }
            } catch (e: Exception) {
                finished = true
                succeeded = false
                errorMsg = e.message
            } finally {
                // Cancel this engine before trying the next one
                if (!succeeded) {
                    engine.cancel(song.id)
                }
            }

            if (succeeded) return@flow
            if (finished) {
                errors.add(errorMsg ?: "Unknown error")
                continue
            }
            // If the flow ended without terminal status, treat as failure
            errors.add("Download ended without completion or failure")
        }
        emit(DownloadResult(
            songId = song.id,
            status = DownloadStatus.FAILED,
            error = "Todos los motores fallaron: ${errors.joinToString("; ")}"
        ))
    }

    override suspend fun cancel(songId: String) {
        engines.forEach { it.cancel(songId) }
    }
}
