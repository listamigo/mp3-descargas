package com.mp3downloader.ui.components

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mp3downloader.domain.service.LruCache
import com.mp3downloader.domain.service.isSafeHttpsUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded in-memory cache so the same thumbnail is decoded at most once. */
private val thumbnailCache = LruCache<String, ImageBitmap>(200)

private val thumbnailClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 8_000
    }
}

private const val MAX_THUMBNAIL_BYTES = 3_000_000

@Composable
actual fun rememberThumbnailBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        // Serve from cache without touching the network.
        thumbnailCache.get(url)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        // Reject non-HTTPS / internal addresses (SSRF guard).
        if (!isSafeHttpsUrl(url)) return@LaunchedEffect

        try {
            val bytes = withContext(Dispatchers.IO) {
                val channel = thumbnailClient.get(url).bodyAsChannel()
                val buffer = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = channel.readAvailable(buf, 0, buf.size)
                    if (read == -1) break
                    total += read
                    if (total > MAX_THUMBNAIL_BYTES) return@withContext null
                    buffer.write(buf, 0, read)
                }
                buffer.toByteArray()
            } ?: return@LaunchedEffect

            decodeScaled(bytes)?.let {
                thumbnailCache.put(url, it)
                bitmap = it
            }
        } catch (_: Exception) {
            // Keep the placeholder icon on any failure.
        }
    }

    return bitmap
}

/** Decode with subsampling so a huge image cannot blow up memory. */
private fun decodeScaled(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val srcW = options.outWidth.takeIf { it > 0 } ?: return null
    val srcH = options.outHeight.takeIf { it > 0 } ?: return null

    val target = 256
    val rawSample = maxOf(1, (maxOf(srcW, srcH) / target))
    // Power-of-two for efficient decoding.
    val sample = Integer.highestOneBit(rawSample)
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    options.inPreferredConfig = Bitmap.Config.RGB_565

    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return decoded.asImageBitmap()
}
