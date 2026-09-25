package com.mp3downloader.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.mp3downloader.ui.theme.AppTheme
import com.mp3downloader.ui.theme.AppearanceSettings
import java.io.File

private const val PREFS_NAME = "mp3downloader_appearance"
private const val KEY_THEME = "theme"
private const val KEY_DARK_MODE = "dark_mode"
private const val KEY_WALLPAPER_URI = "wallpaper_uri"
private const val KEY_WALLPAPER_OPACITY = "wallpaper_opacity"
private const val KEY_COLORS_MIGRATED = "colors_migrated_v2"

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

    // Migrate: force DARK theme on first run after color update
    if (!p.getBoolean(KEY_COLORS_MIGRATED, false)) {
        p.edit().apply {
            putString(KEY_THEME, AppTheme.DARK.name)
            putBoolean(KEY_DARK_MODE, true)
            putBoolean(KEY_COLORS_MIGRATED, true)
            apply()
        }
    }

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

@Composable
actual fun PlatformWallpaper(uri: String, opacity: Float, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        try {
            val decoded = if (uri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uri))
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                android.graphics.BitmapFactory.decodeFile(uri)
                    ?: context.contentResolver.openInputStream(Uri.parse(uri))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
            decoded?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            alpha = opacity
        )
    }
}

@Composable
actual fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onPicked(it.toString()) }
    }
    return { launcher.launch("image/*") }
}
