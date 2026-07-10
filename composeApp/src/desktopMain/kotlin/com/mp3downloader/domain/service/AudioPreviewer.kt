package com.mp3downloader.domain.service

import java.io.File
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

actual class AudioPreviewer {
    @Volatile
    private var clip: Clip? = null
    private var thread: Thread? = null

    actual fun play(url: String, onError: ((String) -> Unit)?) {
        stop()
        thread = Thread {
            try {
                val tempFile = File.createTempFile("preview_", ".wav")
                tempFile.deleteOnExit()

                val process = ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", url,
                    "-f", "wav",
                    "-loglevel", "quiet",
                    tempFile.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()

                val finished = process.waitFor(10, TimeUnit.MINUTES)
                if (!finished) {
                    process.destroyForcibly()
                    tempFile.delete()
                    return@Thread
                }

                if (process.exitValue() == 0 && tempFile.exists() && tempFile.length() > 0) {
                    val audioStream = AudioSystem.getAudioInputStream(tempFile)
                    val newClip = AudioSystem.getClip()
                    clip = newClip
                    newClip.addLineListener { event ->
                        if (event.type == LineEvent.Type.STOP) {
                            newClip.close()
                            if (clip == newClip) clip = null
                            tempFile.delete()
                        }
                    }
                    newClip.open(audioStream)
                    newClip.start()
                } else {
                    tempFile.delete()
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    actual fun stop() {
        thread?.interrupt()
        thread = null
        clip?.apply {
            if (isRunning) stop()
            close()
        }
        clip = null
    }

    actual val isPlaying: Boolean get() = clip?.isRunning == true
}
