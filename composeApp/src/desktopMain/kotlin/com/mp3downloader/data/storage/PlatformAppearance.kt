package com.mp3downloader.data.storage

import com.mp3downloader.ui.theme.AppearanceSettings
import java.io.File

private val prefsFile: File
    get() = File(System.getProperty("user.home"), ".mp3downloader/appearance.json")

actual fun saveAppearance(settings: AppearanceSettings) {
    try {
        prefsFile.parentFile?.mkdirs()
        val json = buildString {
            append("{")
            append("\"theme\":\"${settings.theme.name}\",")
            append("\"isDarkMode\":${settings.isDarkMode},")
            append("\"wallpaperUri\":${if (settings.wallpaperUri != null) "\"${settings.wallpaperUri}\"" else "null"},")
            append("\"wallpaperOpacity\":${settings.wallpaperOpacity}")
            append("}")
        }
        prefsFile.writeText(json)
    } catch (_: Exception) {}
}

actual fun loadAppearance(): AppearanceSettings {
    return try {
        if (prefsFile.exists()) {
            val content = prefsFile.readText().trim()
            val theme = Regex(""""theme"\s*:\s*"(\w+)" """.trimIndent())
                .find(content)?.groupValues?.get(1)
            val isDark = Regex(""""isDarkMode"\s*:\s*(true|false)""".trimIndent())
                .find(content)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: true
            val opacity = Regex(""""wallpaperOpacity"\s*:\s*([\d.]+)""".trimIndent())
                .find(content)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.4f
            val wallpaper = Regex(""""wallpaperUri"\s*:\s*(null|"[^"]*")""".trimIndent())
                .find(content)?.groupValues?.get(1)?.let {
                    if (it == "null") null else it.removeSurrounding("\"")
                }

            AppearanceSettings(
                theme = try { com.mp3downloader.ui.theme.AppTheme.valueOf(theme ?: "DARK") } catch (_: Exception) { com.mp3downloader.ui.theme.AppTheme.DARK },
                isDarkMode = isDark,
                wallpaperUri = wallpaper,
                wallpaperOpacity = opacity
            )
        } else {
            AppearanceSettings()
        }
    } catch (_: Exception) {
        AppearanceSettings()
    }
}
