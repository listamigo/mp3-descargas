package com.mp3downloader.data.engine

import com.mp3downloader.domain.model.DownloadStatus
import com.mp3downloader.domain.model.Song
import com.mp3downloader.domain.service.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * @param beforeSearch hook run before every search. The Android wiring uses it
 *   to warm up the remote host in the background without delaying the query.
 * @param beforeDownload same warm-up, on the download path. It exists because a
 *   download does not always follow a search, and the cold start of a free
 *   container costs 30-50 s of a progress bar stuck at 0%.
 * @param preferredForSearch engines to try first when [lastSearchEngine] has
 *   not pinned pagination yet. Platform code decides this; kept as a hook so
 *   this common class stays free of platform types.
 */
class FallbackEngine(
    private val engines: List<DownloadEngine>,
    private val beforeSearch: suspend () -> Unit = {},
    private val beforeDownload: suspend () -> Unit = {},
    private val preferredForSearch: () -> List<DownloadEngine> = { emptyList() }
) : DownloadEngine {

    private val tag = "FallbackEngine"

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

    /**
     * Hosts whose breaker is open go last, so a host that just failed twice is
     * not asked a third time while a working one is sitting in the list. They
     * stay in the chain on purpose: the trip expires after minutes, and
     * removing them outright would mean no recovery without a restart.
     */
    private fun openBreakersLast(): List<DownloadEngine> = buildList {
        val open = engines.filter { it.isTripped() }
        engines.filter { it !in open }.forEach { add(it) }
        open.forEach { add(it) }
    }

    /**
     * Single ordering rule for search, stream resolution and download:
     * 1. the engine that already answered, so "load more" keeps paging the same
     *    source and the download comes from the backend the user is looking at;
     * 2. engines the platform flagged as worth trying first;
     * 3. the rest, in declaration order, with tripped hosts pushed to the end.
     *
     * Without step 1 on the download path every file paid a full Invidious
     * timeout (~25s here) before reaching a working server.
     */
    private fun orderedEngines(): List<DownloadEngine> = buildList {
        lastSearchEngine?.let { add(it) }
        preferredForSearch().filter { it in engines && it !in this }.forEach { add(it) }
        openBreakersLast().filter { it !in this }.forEach { add(it) }
    }

    override suspend fun search(query: String, offset: Int): Result<List<Song>> {
        val errors = mutableListOf<String>()
        // Fire-and-forget: warms the remote host so the first download does not
        // pay its cold start. Never blocks the query.
        beforeSearch()
        val ordered = orderedEngines()
        for ((i, engine) in ordered.withIndex()) {
            AppLog.d(tag, "search: trying engine[$i]=${engine::class.simpleName} offset=$offset")
            val result = engine.search(query, offset)
            if (result.isSuccess) {
                lastSearchEngine = engine
                AppLog.d(tag, "search: engine[$i] succeeded")
                return result
            }
            val err = result.exceptionOrNull()?.message ?: "Error desconocido"
            AppLog.w(tag, "search: engine[$i] failed: $err")
            errors.add(err)
        }
        return Result.failure(RuntimeException(
            "Todos los motores fallaron: ${errors.joinToString("; ")}"
        ))
    }

    override suspend fun getAudioStreamUrl(song: Song): Result<String> {
        val errors = mutableListOf<String>()
        // Mismo criterio que search(): si la última búsqueda la respondió un
        // motor, se consulta primero. Así la URL de audio sale de la misma
        // fuente que los resultados que el usuario está viendo, en vez de
        // saltar al primero de la lista.
        val ordered = orderedEngines()
        for ((i, engine) in ordered.withIndex()) {
            AppLog.d(tag, "getAudioStreamUrl: trying engine[$i]=${engine::class.simpleName}")
            val result = engine.getAudioStreamUrl(song)
            if (result.isSuccess) return result
            val err = result.exceptionOrNull()?.message ?: "Error desconocido"
            AppLog.w(tag, "getAudioStreamUrl: engine[$i] failed: $err")
            errors.add(err)
        }
        return Result.failure(RuntimeException(
            "Todos los motores fallaron: ${errors.joinToString("; ")}"
        ))
    }

    override fun download(
        song: Song,
        outputDir: String,
        media: MediaKind,
        quality: Int
    ): Flow<DownloadResult> = flow {
        val errors = mutableListOf<String>()
        var cancelled = false

        // Un vídeo solo lo saben producir los motores remotos. Invidious y Piped
        // se quedarían en la cadena y devolverían un MP3 para una petición que
        // pidió un MP4, así que se descartan aquí con un mensaje claro en vez de
        // fallar más tarde con un archivo que no es lo que se pidió.
        val engines = orderedEngines().filter {
            media != MediaKind.VIDEO || it.supportsVideo()
        }
        if (engines.isEmpty()) {
            emit(DownloadResult(
                song.id, DownloadStatus.FAILED,
                error = "Ningún servidor disponible puede entregar vídeo."
            ))
            return@flow
        }

        // Igual que en search: despertar el host remoto en segundo plano. Sin
        // esto, una descarga que no viene precedida de una búsqueda reciente
        // paga los 30-50 s de arranque en frío del contenedor gratis, con la
        // barra de progreso clavada en 0%. El hook no bloquea la descarga.
        beforeDownload()

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
                engine.download(song, outputDir, media, quality).collect { result ->
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

            if (errorMsg == CANCELLED_ERROR) {
                cancelled = true
                break
            }

            if (finished) {
                val logMsg = errorMsg ?: "Unknown error"
                errors.add("${engine::class.simpleName}[intento $attemptNum]: $logMsg")

                // ── Reintentar RemoteServerEngine si el error es transitorio ──
                // Esto cubre el caso crítico: la descarga falló porque el usuario
                // cambió de app o la pantalla se bloqueó. En lugar de saltar a
                // Invidious/Piped (que probablemente también fallarán), esperamos
                // 2 segundos y reintentamos el servidor propio.
                if (isRemoteServerEngine(engine) && isTransientError(errorMsg)) {
                    AppLog.d(tag, "Error transitorio en RemoteServerEngine, reintentando...")
                    delay(2000)

                    var retryFinished = false
                    var retrySucceeded = false
                    var retryError: String? = null

                    try {
                        engine.download(song, outputDir, media, quality).collect { result ->
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
                    if (retryError == CANCELLED_ERROR) {
                        cancelled = true
                        break
                    }
                    errors.add("${engine::class.simpleName}[reintento]: ${retryError ?: "Unknown"}")
                    AppLog.w(tag, "Reintento de RemoteServerEngine falló: $retryError")
                }

                continue
            }

            errors.add("${engine::class.simpleName}: Download ended without completion or failure")
        }

        if (cancelled) {
            emit(DownloadResult(
                songId = song.id,
                status = DownloadStatus.FAILED,
                error = CANCELLED_ERROR
            ))
            return@flow
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
