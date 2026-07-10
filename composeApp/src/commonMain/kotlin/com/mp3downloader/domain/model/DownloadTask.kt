package com.mp3downloader.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadTask(
    val song: Song,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val error: String? = null
)
