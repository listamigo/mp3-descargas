package com.mp3downloader.domain.service

import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import java.io.File
import java.net.URL

object Mp3MetadataWriter {

    private const val TAG = "Mp3MetadataWriter"

    fun writeMetadata(
        filePath: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        genre: String? = null
    ) {
        val file = File(filePath)
        if (!file.exists()) {
            android.util.Log.e(TAG, "File does not exist: $filePath")
            return
        }

        android.util.Log.i(TAG, "Writing MP3 metadata to: $filePath (${file.length()} bytes)")
        android.util.Log.i(TAG, "Title=$title, Artist=$artist, Genre=$genre, Thumb=$thumbnailUrl")

        try {
            // Verify it's a valid MP3 by checking first bytes
            val header = ByteArray(3)
            file.inputStream().use { it.read(header) }
            val isMp3 = header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0
            if (!isMp3) {
                android.util.Log.e(TAG, "File is NOT a valid MP3 (header: ${header.joinToString(" ") { "%02X".format(it) }})")
                // Try to write anyway - mp3agic might handle it
            } else {
                android.util.Log.i(TAG, "File header confirms valid MP3")
            }

            val mp3File = Mp3File(file)
            val tag = ID3v24Tag()

            tag.title = title
            tag.artist = artist
            tag.genreDescription = genre ?: "YouTube Audio"
            tag.album = ""

            if (!thumbnailUrl.isNullOrBlank()) {
                try {
                    android.util.Log.i(TAG, "Downloading thumbnail: $thumbnailUrl")
                    val imageBytes = URL(thumbnailUrl).readBytes()
                    android.util.Log.i(TAG, "Thumbnail downloaded: ${imageBytes.size} bytes")
                    if (imageBytes.isNotEmpty()) {
                        val mimeType = detectMimeType(imageBytes)
                        android.util.Log.i(TAG, "Embedding cover art: $mimeType, ${imageBytes.size} bytes")
                        tag.setAlbumImage(imageBytes, mimeType, 3, "Cover (front)")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to download/embed cover art: ${e.message}", e)
                }
            } else {
                android.util.Log.w(TAG, "No thumbnail URL provided, skipping cover art")
            }

            mp3File.id3v2Tag = tag
            mp3File.save(file.absolutePath)
            android.util.Log.i(TAG, "MP3 metadata saved successfully")

            // Verify the file was written
            val verifyFile = File(file.absolutePath)
            android.util.Log.i(TAG, "File after save: ${verifyFile.length()} bytes")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to write MP3 metadata: ${e.message}", e)
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
