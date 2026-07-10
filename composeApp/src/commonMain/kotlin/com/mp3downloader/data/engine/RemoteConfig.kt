package com.mp3downloader.data.engine

import com.mp3downloader.data.storage.provideStorageDirectory
import java.io.File

object RemoteConfig {
    const val DEFAULT_SERVER_URL = "https://mp3downloader-server-production.up.railway.app"

    private val configFile: File
        get() = File(provideStorageDirectory(), "settings.json")

    var serverUrl: String? = null
        get() {
            if (field == null) {
                field = loadServerUrl() ?: DEFAULT_SERVER_URL
            }
            return field
        }
        set(value) {
            field = value
            saveServerUrl(value)
        }

    private fun loadServerUrl(): String? {
        return try {
            val file = configFile
            if (file.exists()) {
                val content = file.readText().trim()
                if (content == "null" || content.isBlank()) null else content
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveServerUrl(value: String?) {
        try {
            val file = configFile
            file.parentFile?.mkdirs()
            file.writeText(value ?: "null")
        } catch (_: Exception) {}
    }
}
