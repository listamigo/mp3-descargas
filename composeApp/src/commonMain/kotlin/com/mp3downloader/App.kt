package com.mp3downloader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.screens.MainScreen
import com.mp3downloader.ui.theme.Mp3DownloaderTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    Mp3DownloaderTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            val viewModel: MainViewModel = koinInject()
            MainScreen(viewModel = viewModel)
        }
    }
}
