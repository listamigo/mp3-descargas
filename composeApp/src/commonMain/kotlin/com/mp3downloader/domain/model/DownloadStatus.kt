package com.mp3downloader.domain.model

enum class DownloadStatus {
    IDLE,
    QUEUED,
    DOWNLOADING,
    CONVERTING,
    COMPLETED,
    FAILED
}
