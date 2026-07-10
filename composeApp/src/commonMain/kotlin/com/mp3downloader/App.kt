package com.mp3downloader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.screens.MainScreen
import com.mp3downloader.ui.theme.Mp3DownloaderTheme
import com.mp3downloader.ui.theme.ThemeManager
import org.koin.compose.koinInject

@Composable
fun App() {
    val appearance by ThemeManager.settings.collectAsState()

    Mp3DownloaderTheme(
        theme = appearance.theme,
        isDark = appearance.isDarkMode
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            val viewModel: MainViewModel = koinInject()
            MainScreen(viewModel = viewModel)
        }
    }
}
