package com.mp3downloader.domain.service

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

actual class AudioPreviewer {
    private val lock = Any()
    private val mediaPlayerRef = AtomicReference<MediaPlayer?>(null)
    private val generation = AtomicInteger(0)
    private var state = State.IDLE

    private enum class State {
        IDLE,
        PREPARING,
        PLAYING,
        PAUSED
    }

    actual fun play(url: String, onError: ((String) -> Unit)?, onPlaying: (() -> Unit)?) {
        // Tear down any previous player first (this also bumps the generation
        // so its pending callbacks are ignored), then take a fresh generation
        // for THIS player.
        stop()
        val myGen = generation.incrementAndGet()

        val mp = MediaPlayer()
        mediaPlayerRef.set(mp)
        state = State.PREPARING

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Stream the server's progressive MP3 preview directly. Playback
            // starts as soon as enough is buffered, so long mixes no longer
            // need the whole file downloaded first. All YouTube access stays
            // server-side.
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                synchronized(lock) {
                    if (generation.get() != myGen || mediaPlayerRef.get() !== mp) {
                        mp.release()
                        return@setOnPreparedListener
                    }
                }
                state = State.PLAYING
                try {
                    mp.start()
                    onPlaying?.invoke()
                } catch (e: Exception) {
                    state = State.IDLE
                    onError?.invoke(e.message ?: "No se pudo iniciar la reproducción")
                }
            }
            mp.setOnCompletionListener {
                synchronized(lock) {
                    if (generation.get() == myGen && mediaPlayerRef.compareAndSet(mp, null)) {
                        mp.release()
                    }
                }
                state = State.IDLE
            }
            mp.setOnErrorListener { _, what, extra ->
                synchronized(lock) {
                    if (generation.get() == myGen && mediaPlayerRef.compareAndSet(mp, null)) {
                        mp.release()
                    }
                }
                state = State.IDLE
                onError?.invoke("Error de reproducción: what=$what extra=$extra")
                true
            }
            // prepareAsync streams from the network without blocking.
            mp.prepareAsync()
        } catch (e: Exception) {
            synchronized(lock) {
                if (generation.get() == myGen && mediaPlayerRef.compareAndSet(mp, null)) {
                    mp.release()
                }
            }
            state = State.IDLE
            onError?.invoke(e.message ?: "No se pudo preparar la previsualización")
        }
    }

    actual fun pause() {
        val mp = mediaPlayerRef.get()
        if (mp != null && state == State.PLAYING) {
            try {
                mp.pause()
                state = State.PAUSED
            } catch (_: Exception) {
            }
        }
    }

    actual fun resume() {
        val mp = mediaPlayerRef.get()
        if (mp != null && state == State.PAUSED) {
            try {
                mp.start()
                state = State.PLAYING
            } catch (_: Exception) {
            }
        }
    }

    actual fun stop() {
        // Invalidate the current generation first so any pending
        // onPrepared/onError for the old player is ignored.
        generation.incrementAndGet()
        val mp = mediaPlayerRef.getAndSet(null)
        state = State.IDLE
        mp?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {}
            try {
                reset()
            } catch (_: Exception) {}
            try {
                release()
            } catch (_: Exception) {}
        }
    }

    actual val isPlaying: Boolean get() = state == State.PLAYING
    actual val isPaused: Boolean get() = state == State.PAUSED
}
