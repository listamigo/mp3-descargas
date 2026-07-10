package com.mp3downloader.data.repository

import com.mp3downloader.domain.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryData(val downloads: List<DownloadTask>)

class FileHistoryRepository(
    private val storagePath: String
) : HistoryRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val historyFile: File get() = File(storagePath, HISTORY_FILE_NAME)

    override suspend fun loadHistory(): List<DownloadTask> = withContext(Dispatchers.IO) {
        runCatching {
            val file = historyFile
            if (!file.exists()) return@runCatching emptyList<DownloadTask>()
            json.decodeFromString<HistoryData>(file.readText()).downloads
        }.getOrDefault(emptyList())
    }

    override suspend fun saveHistory(tasks: List<DownloadTask>) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(storagePath)
            if (!dir.exists()) dir.mkdirs()
            historyFile.writeText(json.encodeToString(HistoryData(tasks)))
        }
        Unit
    }

    companion object {
        private const val HISTORY_FILE_NAME = "history.json"
    }
}
