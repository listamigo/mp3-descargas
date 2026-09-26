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
     *
     * Railway, not Render. Measured 2026-09-25 on the same video, 3 attempts
     * each, both running the same commit:
     *   Railway: 200 in 0.73-3.18 s to first byte, valid 256 kbps MP3.
     *   Render:  502 every time, after 67-78 s, always ending in
     *            "Invidious fallback: no audio URL available".
     * Render has cookies but its PO token provider never comes up; Railway has
     * no cookies but generates PO tokens in script mode, and that is what lets
     * a datacenter IP extract the audio.
     *
     * A value typed in Settings still takes precedence, so Render (or any
     * other host) can be used instead without touching the code.
     */
    const val DEFAULT_SERVER_URL = "https://mp3downloader-server-production.up.railway.app"

    /**
     * Second host, used only when the primary one fails.
     *
     * Render earns this slot because it is the one deployment that survives
     * Railway running out of credit: Render's free tier is 750 instance
     * hours a month, not a $1 monthly credit that the container burns on
     * memory while it stays up.
     *
     * Slower cold, which is the price: measured 2026-09-25 with the same code
     * on both hosts, first download of a never-requested video took 3,4 s to
     * first byte on Railway and 12,0 s on Render (11,8 MB, 63 s total). Once
     * the container has the file cached both answer in well under a second, so
     * the gap only shows on the first download after the host boots.
     */
    const val DEFAULT_FALLBACK_SERVER_URL = "https://mp3-descargas-1.onrender.com"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredConfig(
        val serverUrl: String? = null,
        val fallbackServerUrl: String? = null,
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

    /**
     * Second-choice host, or [DEFAULT_FALLBACK_SERVER_URL] when unset, so the
     * chain always has a second server without the user configuring anything.
     */
    var fallbackServerUrl: String?
        get() = stored().fallbackServerUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_FALLBACK_SERVER_URL
        set(value) {
            cached = stored().copy(fallbackServerUrl = value?.trim()?.takeIf { it.isNotBlank() })
            save()
        }

    /**
     * Every remote host to try, primary first, without duplicates and without
     * blanks. One engine is built per entry, so a host that the user pointed
     * the primary at is not dialled twice.
     */
    val remoteServerUrls: List<String>
        get() = listOfNotNull(serverUrl, fallbackServerUrl)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()

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
