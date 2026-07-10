package com.mp3downloader.domain.service

import android.util.Log
import org.mp4parser.IsoFile
import org.mp4parser.PropertyBoxParserImpl
import org.mp4parser.boxes.apple.AppleCoverBox
import org.mp4parser.boxes.apple.AppleGenreBox
import org.mp4parser.boxes.apple.AppleItemListBox
import org.mp4parser.boxes.apple.AppleNameBox
import org.mp4parser.boxes.apple.AppleArtistBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.util.Properties

object M4aMetadataWriter {

    private const val TAG = "M4aMetadataWriter"

    private var cachedParser: PropertyBoxParserImpl? = null

    private fun getParser(): PropertyBoxParserImpl {
        cachedParser?.let { return it }
        val context = com.mp3downloader.Mp3DownloaderApp.instance
        val props = Properties()
        context.assets.open("isoparser2-default.properties").use { props.load(it) }
        val parser = PropertyBoxParserImpl(props)
        cachedParser = parser
        return parser
    }

    fun writeMetadata(
        filePath: String,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        genre: String? = null
    ) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: $filePath")
            return
        }

        val ext = file.extension.lowercase()
        Log.i(TAG, "Format: .$ext | size=${file.length()} bytes | title=$title | artist=$artist")

        when (ext) {
            "mp3" -> {
                Log.i(TAG, "Dispatching to Mp3MetadataWriter")
                Mp3MetadataWriter.writeMetadata(filePath, title, artist, thumbnailUrl, genre)
            }
            "m4a", "mp4", "m4b" -> {
                Log.i(TAG, "Writing M4A/MP4 metadata via mp4parser")
                writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
            }
            else -> {
                Log.w(TAG, "Unknown format '.$ext', trying M4A then MP3")
                try {
                    writeM4aMetadata(file, title, artist, thumbnailUrl, genre)
                } catch (e: Exception) {
                    Log.w(TAG, "M4A failed ($ext), trying MP3: ${e.message}")
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
            val parser = getParser()
            val channel = Channels.newChannel(FileInputStream(file))
            isoFile = IsoFile(channel, parser)

            val moovBox = isoFile.movieBox
                ?: throw IllegalStateException("No moov box found — file may not be a valid M4A/MP4 container")

            Log.d(TAG, "moov box found, children: ${moovBox.boxes.size}")

            val udtaBoxes = moovBox.getBoxes(UserDataBox::class.java)
            val udtaBox = if (udtaBoxes.isNotEmpty()) {
                udtaBoxes[0]
            } else {
                UserDataBox().also { moovBox.addBox(it) }
            }

            val ilstBoxes = udtaBox.getBoxes(AppleItemListBox::class.java)
            val ilstBox = if (ilstBoxes.isNotEmpty()) {
                ilstBoxes[0]
            } else {
                AppleItemListBox().also { udtaBox.addBox(it) }
            }

            val nameBox = AppleNameBox()
            nameBox.setValue(title)
            ilstBox.addBox(nameBox)
            Log.d(TAG, "Added title: $title")

            if (artist.isNotBlank()) {
                val artistBox = AppleArtistBox()
                artistBox.setValue(artist)
                ilstBox.addBox(artistBox)
                Log.d(TAG, "Added artist: $artist")
            }

            val genreText = if (!genre.isNullOrBlank()) genre else "YouTube Audio"
            val genreBox = AppleGenreBox()
            genreBox.setValue(genreText)
            ilstBox.addBox(genreBox)
            Log.d(TAG, "Added genre: $genreText")

            if (!thumbnailUrl.isNullOrBlank()) {
                try {
                    Log.d(TAG, "Downloading cover art from: $thumbnailUrl")
                    val imageBytes = downloadWithTimeout(thumbnailUrl, 10_000)
                    if (imageBytes != null && imageBytes.size > 100) {
                        val coverBox = AppleCoverBox()
                        if (isPng(imageBytes)) {
                            coverBox.setPng(imageBytes)
                            Log.d(TAG, "Added PNG cover art (${imageBytes.size} bytes)")
                        } else {
                            coverBox.setJpg(imageBytes)
                            Log.d(TAG, "Added JPEG cover art (${imageBytes.size} bytes)")
                        }
                        ilstBox.addBox(coverBox)
                    } else {
                        Log.w(TAG, "Cover art too small or null (${imageBytes?.size ?: 0} bytes), skipping")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download cover art: ${e.message}")
                }
            } else {
                Log.d(TAG, "No thumbnail URL, skipping cover art")
            }

            saveFile(file, isoFile)
            Log.i(TAG, "M4A metadata written successfully to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write M4A metadata: ${e.message}", e)
            throw e
        } finally {
            try { isoFile?.close() } catch (_: Exception) {}
        }
    }

    private fun saveFile(file: File, isoFile: IsoFile) {
        val tempFile = File(file.parentFile, "${file.name}.meta_tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                isoFile.writeContainer(fos.channel)
            }

            val tempSize = tempFile.length()
            if (tempSize < 1024) {
                throw IllegalStateException("Metadata output too small ($tempSize bytes), aborting")
            }

            if (!file.delete()) {
                throw Exception("Failed to delete original file")
            }
            if (!tempFile.renameTo(file)) {
                throw Exception("Failed to rename temp file to original")
            }
            Log.d(TAG, "File saved: ${file.name} (${file.length()} bytes)")
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun downloadWithTimeout(urlStr: String, timeoutMs: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.connect()
            connection.inputStream.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $urlStr: ${e.message}")
            null
        } finally {
            connection?.disconnect()
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
