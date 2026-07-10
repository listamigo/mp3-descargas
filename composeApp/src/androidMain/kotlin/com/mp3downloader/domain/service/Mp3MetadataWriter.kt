package com.mp3downloader.domain.service

import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.net.URL

object Mp3MetadataWriter {

    fun writeMetadata(
        filePath: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        genre: String? = null
    ) {
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val mp3File = Mp3File(file)
            val tag = ID3v24Tag()

            tag.title = title
            tag.artist = artist
            tag.genreDescription = genre ?: "YouTube Audio"
            tag.album = ""

            if (!thumbnailUrl.isNullOrBlank()) {
                try {
                    val imageBytes = URL(thumbnailUrl).readBytes()
                    if (imageBytes.isNotEmpty()) {
                        val mimeType = detectMimeType(imageBytes)
                        tag.setAlbumImage(imageBytes, mimeType, 3, "Cover (front)")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Mp3MetadataWriter", "Failed to download/embed cover art: ${e.message}")
                }
            }

            mp3File.id3v2Tag = tag
            mp3File.save(file.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("Mp3MetadataWriter", "Failed to write MP3 metadata: ${e.message}")
        }
    }

    private fun detectMimeType(bytes: ByteArray): String {
        return when {
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            else -> "image/jpeg"
        }
    }
}
