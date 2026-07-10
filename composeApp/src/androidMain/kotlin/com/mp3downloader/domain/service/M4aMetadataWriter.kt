package com.mp3downloader.domain.service

import org.jcodec.movtool.MetadataEditor
import org.jcodec.containers.mp4.boxes.MetaValue
import java.io.File
import java.net.URL

object M4aMetadataWriter {

    /**
     * Writes metadata (title, artist, cover art) into an M4A audio file.
     * Uses JCodec's MetadataEditor which handles MP4 box structure safely.
     */
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
                    android.util.Log.w("M4aMetadataWriter", "Failed to download/embed cover art: ${e.message}")
                }
            }

            mediaMeta.save(false)
        } catch (e: Exception) {
            android.util.Log.e("M4aMetadataWriter", "Failed to write metadata: ${e.message}")
        }
    }

    /**
     * Writes metadata including pre-downloaded thumbnail bytes.
     * Use this when you already have the image bytes.
     */
    fun writeMetadataWithImageBytes(
        filePath: String,
        title: String,
        artist: String,
        imageBytes: ByteArray?
    ) {
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val mediaMeta = MetadataEditor.createFrom(file)
            val keyedMeta = mediaMeta.keyedMeta

            keyedMeta["\u00A9nam"] = MetaValue.createString(title)
            keyedMeta["\u00A9art"] = MetaValue.createString(artist)

            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val imageType = detectImageType(imageBytes)
                keyedMeta["covr"] = MetaValue.createOther(imageType, imageBytes)
            }

            mediaMeta.save(false)
        } catch (e: Exception) {
            android.util.Log.e("M4aMetadataWriter", "Failed to write metadata: ${e.message}")
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
