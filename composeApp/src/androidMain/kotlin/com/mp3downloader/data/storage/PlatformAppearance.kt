package com.mp3downloader.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.mp3downloader.ui.theme.AppTheme
import com.mp3downloader.ui.theme.AppearanceSettings
import java.io.File

private const val PREFS_NAME = "mp3downloader_appearance"
private const val KEY_THEME = "theme"
private const val KEY_DARK_MODE = "dark_mode"
private const val KEY_WALLPAPER_URI = "wallpaper_uri"
private const val KEY_WALLPAPER_OPACITY = "wallpaper_opacity"

private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun getAppContext(): Context {
    return try {
        com.mp3downloader.Mp3DownloaderApp.instance
    } catch (_: Exception) {
        throw IllegalStateException("Application not initialized")
    }
}

actual fun saveAppearance(settings: AppearanceSettings) {
    val context = getAppContext()
    prefs(context).edit().apply {
        putString(KEY_THEME, settings.theme.name)
        putBoolean(KEY_DARK_MODE, settings.isDarkMode)
        putString(KEY_WALLPAPER_URI, settings.wallpaperUri)
        putFloat(KEY_WALLPAPER_OPACITY, settings.wallpaperOpacity)
        apply()
    }
}

actual fun loadAppearance(): AppearanceSettings {
    val p = prefs(getAppContext())
    return AppearanceSettings(
        theme = try {
            AppTheme.valueOf(p.getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name)
        } catch (_: Exception) {
            AppTheme.DARK
        },
        isDarkMode = p.getBoolean(KEY_DARK_MODE, true),
        wallpaperUri = p.getString(KEY_WALLPAPER_URI, null),
        wallpaperOpacity = p.getFloat(KEY_WALLPAPER_OPACITY, 0.4f)
    )
}

actual fun persistWallpaperImage(sourceUri: String): String? {
    return try {
        val context = getAppContext()
        val uri = Uri.parse(sourceUri)
        val dir = File(context.filesDir, "wallpaper")
        dir.mkdirs()
        val dest = File(dir, "wallpaper.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.length() > 0) dest.absolutePath else null
    } catch (_: Exception) {
        null
    }
}
