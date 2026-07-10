package com.mp3downloader.data.storage

import com.mp3downloader.ui.theme.AppearanceSettings

expect fun saveAppearance(settings: AppearanceSettings)

expect fun loadAppearance(): AppearanceSettings
