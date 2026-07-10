package com.mp3downloader.domain.service

import org.jcodec.movtool.MetadataEditor
import org.jcodec.containers.mp4.boxes.MetaValue
import java.io.File
import java.net.URL

object M4aMetadataWriter {

    private const val TAG = "M4aMetadataWriter"

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

        val ext = file.extension.lowercase()
        android.util.Log.i(TAG, "Detected format: .$ext for file: $filePath")

        when (ext) {
            "mp3" -> {
                android.util.Log.i(TAG, "Dispatching to Mp3MetadataWriter")
                Mp3MetadataWriter.writeMetadata(filePath, title, artist, thumbnailUrl, genre)
            }
            "m4a", "mp4", "m4b" -> {
                android.util.Log.i(TAG, "Dispatching to JCodec for M4A/MP4")
                writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
            }
            else -> {
                android.util.Log.w(TAG, "Unknown format '.$ext', trying M4A then MP3")
                try {
                    writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "M4A failed, trying MP3: ${e.message}")
                    Mp3MetadataWriter.writeMetadata(filePath, title, artist, thumbnailUrl, genre)
                }
            }
        }
    }

    private fun writeM4aMetadata(
        file: File,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        genre: String?
    ) {
        try {
            val mediaMeta = MetadataEditor.createFrom(file)
            val keyedMeta = mediaMeta.keyedMeta

            keyedMeta["\u00A9nam"] = MetaValue.createString(title)
            keyedMeta["\u00A9art"] = MetaValue.createString(artist)
            keyedMeta["\u00A9gen"] = MetaValue.createString(genre ?: "YouTube Audio")

            if (!thumbnailUrl.isNullOrBlank()) {
                try {
                    val imageBytes = URL(thumbnailUrl).readBytes()
                    val imageType = detectImageType(imageBytes)
                    keyedMeta["covr"] = MetaValue.createOther(imageType, imageBytes)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to download/embed cover art: ${e.message}")
                }
            }

            mediaMeta.save(false)
            android.util.Log.i(TAG, "M4A metadata saved successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to write M4A metadata: ${e.message}")
            throw e
        }
    }

    private fun detectImageType(bytes: ByteArray): Int {
        return when {
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> MetaValue.TYPE_JPEG
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> MetaValue.TYPE_PNG
            bytes.size > 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> MetaValue.TYPE_BMP
            else -> MetaValue.TYPE_JPEG
        }
    }
}
