package com.mp3downloader.domain.service

import android.util.Log
import com.mp3downloader.domain.service.ThumbnailQualityResolver
import com.mp3downloader.domain.service.isSafeHttpsUrl
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight M4A/MP4 metadata writer that manipulates atoms directly.
 * Does NOT load the full file into memory — only reads/modifies the moov atom.
 */
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
                Log.i(TAG, "Writing M4A/MP4 metadata via atom editor")
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
        // Download cover art with quality fallback chain
        // maxresdefault.jpg no existe para videos antiguos → YouTube devuelve placeholder 1x1
        val coverBytes: ByteArray? = if (!thumbnailUrl.isNullOrBlank() && isSafeHttpsUrl(thumbnailUrl)) {
            try {
                val fallbackUrls = ThumbnailQualityResolver.generateFallbackChain(
                    videoId = "",
                    originalUrl = thumbnailUrl
                )
                val minValidSize = 5000  // 5KB mínimo: placeholders de YouTube son < 2KB
                var bestBytes: ByteArray? = null

                for (url in fallbackUrls) {
                    Log.d(TAG, "Downloading cover art from: $url")
                    val bytes = downloadWithTimeout(url, 10_000)
                    if (bytes != null && bytes.size >= minValidSize) {
                        Log.d(TAG, "Cover art OK: ${bytes.size} bytes from $url")
                        bestBytes = bytes
                        break
                    }
                    if (bytes != null) {
                        Log.w(TAG, "Cover art too small (${bytes.size} bytes) at $url, trying next...")
                    } else {
                        Log.w(TAG, "Download failed at $url, trying next...")
                    }
                    bestBytes = bytes  // Keep last result even if small
                }

                if (bestBytes != null && bestBytes.size > 100) {
                    Log.d(TAG, "Cover art final: ${bestBytes.size} bytes")
                    bestBytes
                } else {
                    Log.w(TAG, "Cover art too small (${bestBytes?.size ?: 0} bytes), skipping")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download cover art: ${e.message}")
                null
            }
        } else null

        val raf = RandomAccessFile(file, "rw")
        try {
            // Step 1: Find all top-level atoms and their offsets
            val atoms = scanTopLevelAtoms(raf)
            Log.d(TAG, "Found ${atoms.size} top-level atoms: ${atoms.map { "${it.type}(${it.offset}:${it.size})" }}")

            // Step 2: Find moov atom
            val moovAtom = atoms.find { it.type == "moov" }
                ?: throw IllegalStateException("No moov atom found — not a valid M4A/MP4")

            // Step 3: Read the entire moov atom into memory (it's small, typically <200KB)
            val moovData = ByteArray(moovAtom.size.toInt())
            raf.seek(moovAtom.offset)
            raf.readFully(moovData)
            Log.d(TAG, "moov atom: ${moovData.size} bytes")

            // Step 4: Parse moov children and build modified moov
            val modifiedMoov = injectMetadata(moovData, title, artist, genre, coverBytes)

            // Step 5: Write modified moov back
            // If size changed, we need to handle the shift. Simpler: truncate + rewrite.
            // Since moov is usually before mdat, we rebuild the file.
            rebuildFile(file, atoms, moovAtom, modifiedMoov)

            Log.i(TAG, "M4A metadata written successfully to ${file.name} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write M4A metadata: ${e.message}", e)
            throw e
        } finally {
            raf.close()
        }
    }

    data class AtomInfo(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private fun scanTopLevelAtoms(raf: RandomAccessFile): List<AtomInfo> {
        val atoms = mutableListOf<AtomInfo>()
        var pos = 0L
        val fileLen = raf.length()

        while (pos < fileLen - 8) {
            raf.seek(pos)
            val header = ByteArray(8)
            val read = raf.read(header)
            if (read < 8) break

            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            var size = bb.int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4)

            // Handle extended size (size == 1 means 64-bit size follows)
            val headerSize = if (size == 1L) {
                val ext = ByteArray(8)
                raf.read(ext)
                size = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                16
            } else if (size == 0L) {
                // size == 0 means atom extends to end of file
                size = fileLen - pos
                8
            } else {
                8
            }

            if (type.all { it.isLetterOrDigit() || it == ' ' }) {
                atoms.add(AtomInfo(type, pos, size, headerSize))
            }
            pos += size
        }
        return atoms
    }

    private fun injectMetadata(
        moovData: ByteArray,
        title: String,
        artist: String,
        genre: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val moovBuf = ByteBuffer.wrap(moovData).order(ByteOrder.BIG_ENDIAN)

        // Find udta inside moov
        val children = parseAtomChildren(moovData, 8, moovData.size)
        val udtaChild = children.find { it.type == "udta" }

        val metadataBytes = buildilstAtom(title, artist, genre, coverBytes)

        if (udtaChild != null) {
            // udta exists — find or add ilst inside it
            val udtaData = moovData.copyOfRange(udtaChild.offset.toInt(), (udtaChild.offset + udtaChild.size).toInt())
            val udtaChildren = parseAtomChildren(udtaData, 8, udtaData.size)
            val ilstChild = udtaChildren.find { it.type == "ilst" }

            if (ilstChild != null) {
                // Replace existing ilst
                Log.d(TAG, "Replacing existing ilst atom")
                val before = moovData.copyOfRange(0, ilstChild.offset.toInt())
                val after = moovData.copyOfRange((ilstChild.offset + ilstChild.size).toInt(), moovData.size)
                val sizeDiff = metadataBytes.size - ilstChild.size.toInt()
                val newMoov = ByteArray(moovData.size + sizeDiff)
                ByteBuffer.wrap(newMoov).order(ByteOrder.BIG_ENDIAN).apply {
                    put(before)
                    put(metadataBytes)
                    put(after)
                }
                // Update moov size
                updateAtomSize(newMoov, 0)
                return newMoov
            } else {
                // Add ilst at end of udta
                Log.d(TAG, "Adding ilst to existing udta")
                val insertPos = (udtaChild.offset + udtaChild.size).toInt()
                val newMoov = ByteArray(moovData.size + metadataBytes.size)
                ByteBuffer.wrap(newMoov).order(ByteOrder.BIG_ENDIAN).apply {
                    put(moovData, 0, insertPos)
                    put(metadataBytes)
                    put(moovData, insertPos, moovData.size - insertPos)
                }
                updateAtomSize(newMoov, 0)
                // Update udta size
                val udtaOffsetInMoov = findAtomOffset(newMoov, 8, newMoov.size, "udta")
                if (udtaOffsetInMoov >= 0) updateAtomSize(newMoov, udtaOffsetInMoov)
                return newMoov
            }
        } else {
            // No udta — create udta + ilst
            Log.d(TAG, "Creating new udta with ilst")
            val udtaBytes = buildUdtaAtom(metadataBytes)
            val insertPos = findAtomOffset(moovData, 8, moovData.size, "mdat").let {
                if (it >= 0) it.toInt() else moovData.size
            }
            val newMoov = ByteArray(moovData.size + udtaBytes.size)
            ByteBuffer.wrap(newMoov).order(ByteOrder.BIG_ENDIAN).apply {
                put(moovData, 0, insertPos)
                put(udtaBytes)
                put(moovData, insertPos, moovData.size - insertPos)
            }
            updateAtomSize(newMoov, 0)
            return newMoov
        }
    }

    private fun buildilstAtom(title: String, artist: String, genre: String?, coverBytes: ByteArray?): ByteArray {
        val items = mutableListOf<ByteArray>()

        // Title (©nam)
        items.add(buildMetadataItem("©nam", title.toByteArray(Charsets.UTF_8)))

        // Artist (©ART)
        if (artist.isNotBlank()) {
            items.add(buildMetadataItem("©ART", artist.toByteArray(Charsets.UTF_8)))
        }

        // Genre (©gen)
        val genreText = if (!genre.isNullOrBlank()) genre else "YouTube Audio"
        items.add(buildMetadataItem("©gen", genreText.toByteArray(Charsets.UTF_8)))

        // Cover art (covr)
        if (coverBytes != null) {
            items.add(buildCoverItem(coverBytes))
        }

        // Calculate total ilst size
        val totalSize = items.sumOf { it.size }
        val ilstBuf = ByteBuffer.allocate(8 + totalSize).order(ByteOrder.BIG_ENDIAN)
        ilstBuf.putInt(8 + totalSize)
        ilstBuf.put("ilst".toByteArray(Charsets.US_ASCII))
        items.forEach { ilstBuf.put(it) }

        return ilstBuf.array()
    }

    private fun buildMetadataItem(key: String, value: ByteArray): ByteArray {
        // Atom structure: size + type + flags(3 bytes) + data atom
        val dataAtom = buildDataAtom(value)
        val itemSize = 8 + 3 + dataAtom.size // header + flags + data
        val buf = ByteBuffer.allocate(itemSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(itemSize)
        buf.put(key.toByteArray(Charsets.US_ASCII))
        buf.put(ByteArray(3)) // flags = 0
        buf.put(dataAtom)
        return buf.array()
    }

    private fun buildDataAtom(value: ByteArray): ByteArray {
        // data atom: size + "data" + type(4 bytes: 0,0,0,1 for UTF-8) + locale(4 bytes: 0,0,0,0) + value
        val size = 8 + 4 + 4 + value.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(1) // type: UTF-8
        buf.putInt(0) // locale
        buf.put(value)
        return buf.array()
    }

    private fun buildCoverItem(imageBytes: ByteArray): ByteArray {
        // covr item: size + "covr" + flags(3) + data atom (type=0x0D for JPEG, 0x0E for PNG)
        val imageType = if (isPng(imageBytes)) 14 else 13
        val dataAtomSize = 8 + 4 + 4 + imageBytes.size
        val itemSize = 8 + 3 + dataAtomSize
        val buf = ByteBuffer.allocate(itemSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(itemSize)
        buf.put("covr".toByteArray(Charsets.US_ASCII))
        buf.put(ByteArray(3)) // flags
        // data atom
        buf.putInt(dataAtomSize)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(imageType) // type: JPEG=13, PNG=14
        buf.putInt(0) // locale
        buf.put(imageBytes)
        return buf.array()
    }

    private fun buildUdtaAtom(ilstBytes: ByteArray): ByteArray {
        val size = 8 + ilstBytes.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("udta".toByteArray(Charsets.US_ASCII))
        buf.put(ilstBytes)
        return buf.array()
    }

    private fun parseAtomChildren(data: ByteArray, start: Int, end: Int): List<AtomInfo> {
        val children = mutableListOf<AtomInfo>()
        var pos = start
        while (pos < end - 8) {
            val size = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (size < 8 || pos + size > end) break
            val type = String(data, pos + 4, 4)
            children.add(AtomInfo(type, pos.toLong(), size, 8))
            pos += size.toInt()
        }
        return children
    }

    private fun findAtomOffset(data: ByteArray, start: Int, end: Int, type: String): Int {
        var pos = start
        while (pos < end - 8) {
            val size = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (size < 8) break
            val atomType = String(data, pos + 4, 4)
            if (atomType == type) return pos
            pos += size.toInt()
        }
        return -1
    }

    private fun updateAtomSize(data: ByteArray, offset: Int) {
        var totalSize = 0L
        var pos = offset + 8 // skip size + type
        val type = String(data, offset + 4, 4)

        // For moov: sum all children. For others: just recalculate.
        while (pos < data.size - 8) {
            val childSize = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (childSize < 8 || pos + childSize > data.size) break
            totalSize += childSize
            pos += childSize.toInt()
        }
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt((totalSize + 8).toInt())
    }

    private fun rebuildFile(file: File, atoms: List<AtomInfo>, moovAtom: AtomInfo, newMoovData: ByteArray) {
        val tempFile = File(file.parentFile, "${file.name}.meta_tmp")
        try {
            val srcRaf = RandomAccessFile(file, "r")
            val dstRaf = RandomAccessFile(tempFile, "rw")

            try {
                // Write everything BEFORE moov
                if (moovAtom.offset > 0) {
                    srcRaf.seek(0)
                    copyBytes(srcRaf, dstRaf, moovAtom.offset)
                }

                // Write modified moov
                dstRaf.write(newMoovData)

                // Write everything AFTER the ORIGINAL moov position.
                // NOTA: Usamos moovAtom.offset + moovAtom.size (posición original
                // después del moov) para leer los datos desde el archivo fuente.
                // El nuevo moov puede ser más grande (cover art), pero eso no
                // afecta la lectura de los datos posteriores desde el source.
                val afterMoovOffset = moovAtom.offset + moovAtom.size
                if (afterMoovOffset < file.length()) {
                    srcRaf.seek(afterMoovOffset)
                    copyBytes(srcRaf, dstRaf, file.length() - afterMoovOffset)
                }
            } finally {
                srcRaf.close()
                dstRaf.close()
            }

            if (!file.delete()) throw Exception("Failed to delete original file")
            if (!tempFile.renameTo(file)) throw Exception("Failed to rename temp file")
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun copyBytes(src: RandomAccessFile, dst: RandomAccessFile, length: Long) {
        val buf = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = src.read(buf, 0, toRead)
            if (read == -1) break
            dst.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun downloadWithTimeout(urlStr: String, timeoutMs: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
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
