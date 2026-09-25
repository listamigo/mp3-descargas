package com.mp3downloader.ui.screens

import androidx.compose.runtime.Composable

@Composable
expect fun rememberVoiceSearchLauncher(
    onResult: (String) -> Unit,
    onError: ((String) -> Unit)? = null
): () -> Unit
