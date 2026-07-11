package com.mp3downloader.domain.service

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA")
expect class AudioPreviewer() {
    fun play(url: String, onError: ((String) -> Unit)? = null, onPlaying: (() -> Unit)? = null)
    fun pause()
    fun resume()
    fun stop()
    val isPlaying: Boolean
    val isPaused: Boolean
}
