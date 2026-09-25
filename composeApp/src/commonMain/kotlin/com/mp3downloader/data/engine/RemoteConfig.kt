package com.mp3downloader.data.engine

import com.mp3downloader.data.storage.provideStorageDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object RemoteConfig {
    /**
     * Shipped as the default so the app works out of the box. A fresh install
     * has no stored config, and a reinstall wipes it, so without this the app
     * would ship with no working engine until the user typed a URL by hand.
     * Verified 2026-09-25: /api/search returns 20 results in ~5 s and
     * /api/download returns valid 256 kbps MP3.
     *
     * A value typed in Settings still takes precedence, so anyone can point
     * the app at their own host or a backup.
     */
    const val DEFAULT_SERVER_URL = "https://mp3-descargas-1.onrender.com"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredConfig(
        val serverUrl: String? = null,
        val invidiousUrl: String? = null,
        val pipedUrl: String? = null,
    )

    private val configFile: File
        get() = File(provideStorageDirectory(), "settings.json")

    // Loaded lazily: on Android provideStorageDirectory() depends on
    // AndroidStorage.basePath, which is only assigned in Application.onCreate.
    private var cached: StoredConfig? = null

    private fun stored(): StoredConfig {
        val current = cached
        if (current != null) return current
        val loaded = load()
        cached = loaded
        return loaded
    }

    /**
     * The configured host, or [DEFAULT_SERVER_URL] when nothing is stored so
     * the remote engine is never dead on arrival. Callers still treat a blank
     * value as "engine unavailable" and fall back immediately.
     */
    var serverUrl: String?
        get() = stored().serverUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_URL
        set(value) {
            cached = stored().copy(serverUrl = value?.trim()?.takeIf { it.isNotBlank() })
            save()
        }

    var invidiousUrl: String?
        get() = stored().invidiousUrl?.takeIf { it.isNotBlank() }
        set(value) {
            cached = stored().copy(invidiousUrl = value?.trim()?.takeIf { it.isNotBlank() })
            save()
        }

    var pipedUrl: String?
        get() = stored().pipedUrl?.takeIf { it.isNotBlank() }
        set(value) {
            cached = stored().copy(pipedUrl = value?.trim()?.takeIf { it.isNotBlank() })
            save()
        }

    private fun load(): StoredConfig {
        return try {
            val file = configFile
            if (!file.exists()) {
                StoredConfig()
            } else {
                val content = file.readText().trim()
                when {
                    content.isEmpty() || content == "null" -> StoredConfig()
                    // Settings written by older versions stored only the raw URL.
                    !content.startsWith("{") -> StoredConfig(serverUrl = content.trim())
                    else -> json.decodeFromString<StoredConfig>(content)
                }
            }
        } catch (_: Exception) {
            StoredConfig()
        }
    }

    private fun save() {
        try {
            val file = configFile
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(stored()))
        } catch (_: Exception) {}
    }
}
