package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FallbackEngine(
    private val engines: List<DownloadEngine>
) : DownloadEngine {

    // Remembers the engine that handled the last successful search so that
    // subsequent "load more" pages come from the same source.
    private var lastSearchEngine: DownloadEngine? = null

    /**
     * Errores de conexión que pueden ser transitorios (por ejemplo, al
     * volver de background o después de bloquear pantalla). En estos
     * casos conviene reintentar antes de saltar al siguiente motor.
     */
    private val transientErrors = listOf(
        "connection abort",
        "connection reset",
        "broken pipe",
        "timeout",
        "unable to resolve host",
        "no address associated",
        "software caused connection",
        "socket closed",
        "read timed out",
        "connect timed out",
    )

    private fun isTransientError(message: String?): Boolean {
        val msg = message?.lowercase() ?: return false
        return transientErrors.any { msg.contains(it) }
    }

    private fun isRemoteServerEngine(engine: DownloadEngine): Boolean {
        return engine::class.simpleName == "RemoteServerEngine"
    }

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

        // Primera pasada: intentar cada motor una vez
        val attempts = mutableListOf<Pair<DownloadEngine, Int>>()
        engines.forEachIndexed { idx, engine ->
            // Primer intento
            attempts.add(engine to (idx + 1)) // +1 indica intento 1
        }

        for ((engine, attemptNum) in attempts) {
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
                if (!succeeded) {
                    engine.cancel(song.id)
                }
            }

            if (succeeded) return@flow

            if (finished) {
                val logMsg = errorMsg ?: "Unknown error"
                errors.add("${engine::class.simpleName}[intento $attemptNum]: $logMsg")

                // ── Reintentar RemoteServerEngine si el error es transitorio ──
                // Esto cubre el caso crítico: la descarga falló porque el usuario
                // cambió de app o la pantalla se bloqueó. En lugar de saltar a
                // Invidious/Piped (que probablemente también fallarán), esperamos
                // 2 segundos y reintentamos el servidor propio.
                if (isRemoteServerEngine(engine) && isTransientError(errorMsg)) {
                    android.util.Log.w("FallbackEngine",
                        "Error transitorio en RemoteServerEngine, reintentando...")
                    delay(2000)

                    var retryFinished = false
                    var retrySucceeded = false
                    var retryError: String? = null

                    try {
                        engine.download(song, outputDir).collect { result ->
                            if (result.status == DownloadStatus.COMPLETED) {
                                emit(result)
                                retrySucceeded = true
                                retryFinished = true
                            } else if (result.status == DownloadStatus.FAILED) {
                                retryFinished = true
                                retrySucceeded = false
                                retryError = result.error
                            } else {
                                emit(result)
                            }
                        }
                    } catch (e: Exception) {
                        retryFinished = true
                        retrySucceeded = false
                        retryError = e.message
                    } finally {
                        if (!retrySucceeded) {
                            engine.cancel(song.id)
                        }
                    }

                    if (retrySucceeded) return@flow
                    errors.add("${engine::class.simpleName}[reintento]: ${retryError ?: "Unknown"}")
                    android.util.Log.w("FallbackEngine",
                        "Reintento de RemoteServerEngine falló: ${retryError}")
                }

                continue
            }

            errors.add("${engine::class.simpleName}: Download ended without completion or failure")
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
