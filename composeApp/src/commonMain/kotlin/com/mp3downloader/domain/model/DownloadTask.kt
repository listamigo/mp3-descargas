package com.mp3downloader.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DownloadTask(
    val song: Song,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f,
    val outputPath: String? = null,
    val error: String? = null,
    val fileSizeBytes: Long = 0L,
    /** Bytes written so far, shown next to the bar during the transfer. */
    val downloadedBytes: Long = 0L,
    /** Current throughput, so a slow host is visibly different from a fast one. */
    val bytesPerSecond: Long = 0L
)
