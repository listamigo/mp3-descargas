package com.mp3downloader.domain.service

import com.mp3downloader.domain.service.ThumbnailQualityResolver
import com.mp3downloader.domain.service.isSafeHttpsUrl
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL

object Mp3MetadataWriter {

    private const val TAG = "Mp3MetadataWriter"
    private const val COVER_ART_MIN_SIZE = 5000

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
            val audioFile = AudioFileIO.read(file)

            // Usamos SIEMPRE un ID3v24Tag, que soporta APIC (attached picture frames).
            // El tag existente (si hay) puede ser ID3v1 o ID3v23, y ninguno de ellos
            // maneja correctamente imágenes en todos los reproductores.
            // ID3v24Tag es el estándar más compatible para carátulas.
            val tag = ID3v24Tag()

            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, "")
            tag.setField(FieldKey.GENRE, genre ?: "YouTube Audio")

            // Download and embed cover art
            if (!thumbnailUrl.isNullOrBlank() && isSafeHttpsUrl(thumbnailUrl)) {
                try {
                    val fallbackUrls = ThumbnailQualityResolver.generateFallbackChain(
                        videoId = "",
                        originalUrl = thumbnailUrl
                    )
                    var bestBytes: ByteArray? = null

                    for (url in fallbackUrls) {
                        android.util.Log.i(TAG, "Downloading thumbnail: $url")
                        val bytes = URL(url).readBytes()
                        if (bytes.size >= COVER_ART_MIN_SIZE) {
                            android.util.Log.i(TAG, "Thumbnail OK: ${bytes.size} bytes from $url")
                            bestBytes = bytes
                            break
                        }
                        android.util.Log.w(TAG, "Thumbnail too small (${bytes.size} bytes) at $url, trying next...")
                        bestBytes = bytes
                    }

                    if (bestBytes != null && bestBytes.isNotEmpty()) {
                        val mimeType = detectMimeType(bestBytes)
                        android.util.Log.i(TAG, "Embedding cover art: $mimeType, ${bestBytes.size} bytes")
                        // ArtworkFactory crea un Artwork con pictureType=3 (Cover front) por defecto
                        val artwork = ArtworkFactory.getNew()
                        artwork.binaryData = bestBytes
                        artwork.mimeType = mimeType
                        tag.setField(artwork)
                    } else {
                        android.util.Log.w(TAG, "No valid cover art found, skipping")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to download/embed cover art: ${e.message}", e)
                }
            } else {
                android.util.Log.w(TAG, "No thumbnail URL provided, skipping cover art")
            }

            // Asignar el nuevo tag y escribir al archivo
            audioFile.tag = tag
            audioFile.commit()
            android.util.Log.i(TAG, "MP3 metadata saved successfully (${file.length()} bytes)")

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
