package com.mp3downloader.domain.model

import com.mp3downloader.data.engine.MediaKind

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
    val bytesPerSecond: Long = 0L,
    /** Qué se pidió al servidor: MP3 o MP4. */
    val media: MediaKind = MediaKind.AUDIO,
    /** Altura del vídeo en píxeles; 0 cuando [media] es [MediaKind.AUDIO]. */
    val quality: Int = 0
)
