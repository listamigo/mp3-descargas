package com.mp3downloader.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceSettings(
    val theme: AppTheme = AppTheme.DARK,
    val isDarkMode: Boolean = true,
    val wallpaperUri: String? = null,
    val wallpaperOpacity: Float = 0.4f
)

object ThemeManager {
    private val _settings = MutableStateFlow(AppearanceSettings())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    fun updateTheme(theme: AppTheme) {
        _settings.value = _settings.value.copy(theme = theme)
    }

    fun updateDarkMode(isDark: Boolean) {
        _settings.value = _settings.value.copy(isDarkMode = isDark)
    }

    fun updateWallpaper(uri: String?) {
        _settings.value = _settings.value.copy(wallpaperUri = uri)
    }

    fun updateWallpaperOpacity(opacity: Float) {
        _settings.value = _settings.value.copy(wallpaperOpacity = opacity)
    }

    fun loadFrom(saved: AppearanceSettings) {
        _settings.value = saved
    }
}
