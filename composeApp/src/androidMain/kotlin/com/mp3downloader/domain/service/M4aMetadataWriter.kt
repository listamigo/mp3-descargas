package com.mp3downloader.domain.service

import org.mp4parser.IsoFile
import org.mp4parser.boxes.apple.AppleCoverBox
import org.mp4parser.boxes.apple.AppleGenreBox
import org.mp4parser.boxes.apple.AppleItemListBox
import org.mp4parser.boxes.apple.AppleNameBox
import org.mp4parser.boxes.apple.AppleArtistBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import java.io.File
import java.io.FileOutputStream
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
                android.util.Log.i(TAG, "Dispatching to mp4parser for M4A/MP4")
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
        var isoFile: IsoFile? = null
        try {
            isoFile = IsoFile(file.absolutePath)
            val moovBox = isoFile.movieBox

            val udtaBoxes = moovBox.getBoxes(UserDataBox::class.java)
            var udtaBox = udtaBoxes.firstOrNull()
            if (udtaBox == null) {
                udtaBox = UserDataBox()
                moovBox.addBox(udtaBox)
            }

            val ilstBoxes = udtaBox.getBoxes(AppleItemListBox::class.java)
            var ilstBox = ilstBoxes.firstOrNull()
            if (ilstBox == null) {
                ilstBox = AppleItemListBox()
                udtaBox.addBox(ilstBox)
            }

            val nameBox = AppleNameBox()
            nameBox.setValue(title)
            ilstBox.addBox(nameBox)

            if (artist.isNotBlank()) {
                val artistBox = AppleArtistBox()
                artistBox.setValue(artist)
                ilstBox.addBox(artistBox)
            }

            val genreText = if (!genre.isNullOrBlank()) genre else "YouTube Audio"
            val genreBox = AppleGenreBox()
            genreBox.setValue(genreText)
            ilstBox.addBox(genreBox)

            if (!thumbnailUrl.isNullOrBlank()) {
                try {
                    val imageBytes = URL(thumbnailUrl).readBytes()
                    val coverBox = AppleCoverBox()
                    if (isPng(imageBytes)) {
                        coverBox.setPng(imageBytes)
                    } else {
                        coverBox.setJpg(imageBytes)
                    }
                    ilstBox.addBox(coverBox)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to download/embed cover art: ${e.message}")
                }
            }

            saveFile(file, isoFile)
            android.util.Log.i(TAG, "M4A metadata saved successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to write M4A metadata: ${e.message}")
            throw e
        } finally {
            isoFile?.close()
        }
    }

    private fun saveFile(file: File, isoFile: IsoFile) {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                isoFile.writeContainer(fos.channel)
            }

            if (!file.delete()) {
                throw Exception("Failed to delete original file")
            }
            if (!tempFile.renameTo(file)) {
                throw Exception("Failed to rename temp file")
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun isPng(bytes: ByteArray): Boolean {
        return bytes.size > 3 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
    }
}
