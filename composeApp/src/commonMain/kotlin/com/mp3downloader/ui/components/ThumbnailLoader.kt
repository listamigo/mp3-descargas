package com.mp3downloader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
expect fun rememberThumbnailBitmap(url: String?): ImageBitmap?
