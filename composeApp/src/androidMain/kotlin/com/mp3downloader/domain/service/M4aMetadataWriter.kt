package com.mp3downloader.domain.service

import org.jcodec.movtool.MetadataEditor
import org.jcodec.containers.mp4.boxes.MetaValue
import java.io.File
import java.net.URL

object M4aMetadataWriter {

    /**
     * Auto-detects file format and writes metadata accordingly.
     * MP3 → Mp3MetadataWriter (ID3v2), M4A/MP4 → JCodec (MP4 boxes).
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

        val ext = file.extension.lowercase()
        when (ext) {
            "mp3" -> {
                Mp3MetadataWriter.writeMetadata(filePath, title, artist, thumbnailUrl, genre)
            }
            "m4a", "mp4", "m4b" -> {
                writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
            }
            else -> {
                // Try M4A first, fallback to MP3
                try {
                    writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
                } catch (_: Exception) {
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
                    android.util.Log.w("M4aMetadataWriter", "Failed to download/embed cover art: ${e.message}")
                }
            }

            mediaMeta.save(false)
        } catch (e: Exception) {
            android.util.Log.e("M4aMetadataWriter", "Failed to write M4A metadata: ${e.message}")
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
