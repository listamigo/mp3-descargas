package com.mp3downloader.domain.service

import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import com.mp3downloader.domain.service.isSafeHttpsUrl
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
            // Check header: FF FB/FF F3 = MPEG sync, 49 44 33 = "ID3" (valid with existing tags)
            val header = ByteArray(3)
            file.inputStream().use { it.read(header) }
            val hasMpegSync = header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0
            val hasId3Tag = header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()
            android.util.Log.i(TAG, "Header: ${header.joinToString(" ") { "%02X".format(it) }} (MPEG=$hasMpegSync, ID3=$hasId3Tag)")

            val mp3File = Mp3File(file)
            val tag = ID3v24Tag()

            tag.title = title
            tag.artist = artist
            tag.genreDescription = genre ?: "YouTube Audio"
            tag.album = ""

            if (!thumbnailUrl.isNullOrBlank() && isSafeHttpsUrl(thumbnailUrl)) {
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

            // mp3agic requires saving to a different filename, then rename
            val tmpFile = File(file.parentFile, "${file.nameWithoutExtension}_tmp.mp3")
            mp3File.save(tmpFile.absolutePath)
            android.util.Log.i(TAG, "Saved to temp: ${tmpFile.length()} bytes")

            // Replace original with tagged version
            if (file.delete() && tmpFile.renameTo(file)) {
                android.util.Log.i(TAG, "MP3 metadata saved successfully (${file.length()} bytes)")
            } else {
                android.util.Log.e(TAG, "Failed to replace original file")
                tmpFile.delete()
            }
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
