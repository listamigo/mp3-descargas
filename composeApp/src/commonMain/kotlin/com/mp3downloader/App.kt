package com.mp3downloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.screens.MainScreen
import com.mp3downloader.ui.screens.SplashScreen
import com.mp3downloader.ui.theme.Mp3DownloaderTheme
import com.mp3downloader.ui.theme.ThemeManager
import org.koin.compose.koinInject

@Composable
fun App() {
    val appearance by ThemeManager.settings.collectAsState()
    var splashFinished by remember { mutableStateOf(false) }

    Mp3DownloaderTheme(
        theme = appearance.theme,
        isDark = appearance.isDarkMode
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background
        ) {
            if (!splashFinished) {
                SplashScreen(onSplashFinished = { splashFinished = true })
            } else {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val viewModel: MainViewModel = koinInject()
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
