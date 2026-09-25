package com.mp3downloader.ui.screens

import androidx.compose.runtime.Composable

@Composable
actual fun rememberVoiceSearchLauncher(
    onResult: (String) -> Unit,
    onError: ((String) -> Unit)?
): () -> Unit {
    return {}
}
