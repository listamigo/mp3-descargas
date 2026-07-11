package com.mp3downloader.data.storage

import com.mp3downloader.ui.theme.AppearanceSettings

expect fun saveAppearance(settings: AppearanceSettings)

expect fun loadAppearance(): AppearanceSettings

/**
 * Copies the image referenced by [sourceUri] into app-private storage so the
 * wallpaper survives app restarts (a raw `content://` picker URI loses its
 * permission once the process is killed). Returns the persistent file path,
 * or null if the copy failed.
 */
expect fun persistWallpaperImage(sourceUri: String): String?
