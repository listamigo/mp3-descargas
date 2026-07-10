package com.mp3downloader.data.repository

import com.mp3downloader.domain.model.DownloadTask

interface HistoryRepository {
    suspend fun loadHistory(): List<DownloadTask>
    suspend fun saveHistory(tasks: List<DownloadTask>)
}
