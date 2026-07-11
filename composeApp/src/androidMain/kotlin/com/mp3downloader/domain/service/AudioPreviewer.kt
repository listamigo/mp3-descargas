package com.mp3downloader.domain.service

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

actual class AudioPreviewer {
    private val lock = Any()
    private val mediaPlayerRef = AtomicReference<MediaPlayer?>(null)
    private var state = State.IDLE

    private enum class State {
        IDLE,
        PREPARING,
        PLAYING
    }

    actual fun play(url: String, onError: ((String) -> Unit)?, onPlaying: (() -> Unit)?) {
        stop()
        state = State.PREPARING

        val mp = MediaPlayer()
        mediaPlayerRef.set(mp)

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Stream the server's progressive MP3 preview directly. Playback
            // starts as soon as enough is buffered, so long mixes no longer
            // need the whole file downloaded first (and never hit a read
            // timeout). All YouTube access stays server-side.
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                synchronized(lock) {
                    if (mediaPlayerRef.get() !== mp) {
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
                    if (mediaPlayerRef.compareAndSet(mp, null)) mp.release()
                }
                state = State.IDLE
            }
            mp.setOnErrorListener { _, what, extra ->
                synchronized(lock) {
                    if (mediaPlayerRef.compareAndSet(mp, null)) mp.release()
                }
                state = State.IDLE
                onError?.invoke("Error de reproducción: what=$what extra=$extra")
                true
            }
            // prepareAsync streams from the network without blocking.
            mp.prepareAsync()
        } catch (e: Exception) {
            state = State.IDLE
            synchronized(lock) {
                if (mediaPlayerRef.compareAndSet(mp, null)) mp.release()
            }
            onError?.invoke(e.message ?: "No se pudo preparar la previsualización")
        }
    }

    actual fun stop() {
        val mp = mediaPlayerRef.getAndSet(null)
        state = State.IDLE
        mp?.apply {
            try {
                if (isPlaying) stop()
            } catch (_: Exception) {}
            try {
                release()
            } catch (_: Exception) {}
        }
    }

    actual val isPlaying: Boolean get() = state == State.PLAYING
}
