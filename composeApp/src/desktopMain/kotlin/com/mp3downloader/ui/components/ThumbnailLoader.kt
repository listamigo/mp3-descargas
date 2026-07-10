package com.mp3downloader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
actual fun rememberThumbnailBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        try {
            val bytes = withContext(Dispatchers.IO) {
                java.net.URI(url).toURL().readBytes()
            }
            bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {}
    }

    return bitmap
}
