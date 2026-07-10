package com.mp3downloader.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberThumbnailBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        try {
            val bytes = withContext(Dispatchers.IO) {
                val client = HttpClient()
                try {
                    val channel = client.get(url).bodyAsChannel()
                    val buffer = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        if (read == -1) break
                        buffer.write(buf, 0, read)
                    }
                    buffer.toByteArray()
                } finally {
                    client.close()
                }
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
        } catch (_: Exception) {}
    }

    return bitmap
}
