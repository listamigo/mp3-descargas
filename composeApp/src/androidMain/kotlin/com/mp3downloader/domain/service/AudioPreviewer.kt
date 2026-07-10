package com.mp3downloader.domain.service

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.Volatile

actual class AudioPreviewer {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null
    private var state = State.IDLE

    @Volatile
    private var cancelDownload = false

    private enum class State {
        IDLE,
        DOWNLOADING,
        PLAYING
    }

    actual fun play(url: String, onError: ((String) -> Unit)?, onPlaying: (() -> Unit)?) {
        stop()
        state = State.DOWNLOADING

        val file: File = try {
            File.createTempFile("preview_", ".m4a")
        } catch (e: Exception) {
            onError?.invoke("Cannot create temp file: ${e.message}")
            state = State.IDLE
            return
        }
        synchronized(lock) { tempFile = file }

        Thread {
            try {
                cancelDownload = false
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
                connection.instanceFollowRedirects = true

                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText() ?: ""
                    } catch (_: Exception) { "" }
                    val msg = if (errorBody.length in 10..500) errorBody else "Server returned HTTP $statusCode"
                    onError?.invoke(msg)
                    cleanupAfterError(file)
                    return@Thread
                }

                val contentType = connection.contentType
                if (contentType != null && !contentType.startsWith("audio/") && !contentType.startsWith("application/octet-stream")) {
                    onError?.invoke("Unexpected content type: $contentType")
                    cleanupAfterError(file)
                    return@Thread
                }

                val inputStream = connection.inputStream
                FileOutputStream(file).use { out ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelDownload) {
                            inputStream.close()
                            synchronized(lock) {
                                if (file.exists()) file.delete()
                                tempFile = null
                            }
                            state = State.IDLE
                            return@Thread
                        }
                        out.write(buffer, 0, bytesRead)
                    }
                }
                inputStream.close()

                if (cancelDownload) {
                    synchronized(lock) {
                        if (file.exists()) file.delete()
                        tempFile = null
                    }
                    state = State.IDLE
                    return@Thread
                }

                if (file.length() == 0L) {
                    onError?.invoke("Downloaded file is empty")
                    cleanupAfterError(file)
                    return@Thread
                }

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnPreparedListener {
                        state = State.PLAYING
                        start()
                        onPlaying?.invoke()
                    }
                    setOnCompletionListener {
                        synchronized(lock) {
                            if (mediaPlayer === this) {
                                mediaPlayer = null
                            }
                        }
                        release()
                        if (file.exists()) file.delete()
                        state = State.IDLE
                    }
                    setOnErrorListener { _, what, extra ->
                        synchronized(lock) {
                            if (mediaPlayer === this) {
                                mediaPlayer = null
                            }
                        }
                        release()
                        if (file.exists()) file.delete()
                        state = State.IDLE
                        onError?.invoke("Playback error: what=$what extra=$extra")
                        true
                    }
                    prepare()
                }

                synchronized(lock) {
                    if (cancelDownload || mediaPlayer != null) {
                        mp.release()
                    } else {
                        mediaPlayer = mp
                        state = State.PLAYING
                        mp.start()
                    }
                }
                if (!cancelDownload) {
                    onPlaying?.invoke()
                }
            } catch (e: Exception) {
                state = State.IDLE
                cleanupAfterError(file)
                onError?.invoke(e.message ?: "Download failed")
            }
        }.apply { isDaemon = true; start() }
    }

    actual fun stop() {
        cancelDownload = true
        synchronized(lock) {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                } catch (_: Exception) {}
                try {
                    release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null
            tempFile?.let {
                if (it.exists()) it.delete()
            }
            tempFile = null
        }
        state = State.IDLE
    }

    actual val isPlaying: Boolean get() = state == State.PLAYING

    private fun cleanupAfterError(file: File) {
        synchronized(lock) {
            if (file.exists()) file.delete()
            tempFile = null
        }
    }
}
