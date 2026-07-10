package com.mp3downloader

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mp3downloader.di.sharedModules
import org.koin.core.context.startKoin

fun main() = application {
    startKoin {
        modules(sharedModules)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "MP3 Downloader",
        state = rememberWindowState(
            size = DpSize(960.dp, 680.dp)
        )
    ) {
        App()
    }
}
