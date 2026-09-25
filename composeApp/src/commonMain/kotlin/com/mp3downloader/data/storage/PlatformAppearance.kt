package com.mp3downloader.data.storage

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mp3downloader.ui.theme.AppearanceSettings

expect fun saveAppearance(settings: AppearanceSettings)

expect fun loadAppearance(): AppearanceSettings

expect fun persistWallpaperImage(sourceUri: String): String?

@Composable
expect fun PlatformWallpaper(uri: String, opacity: Float, modifier: Modifier)

@Composable
expect fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit
