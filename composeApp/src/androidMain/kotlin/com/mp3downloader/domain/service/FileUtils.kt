package com.mp3downloader.domain.service

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mp3downloader.domain.service.sanitizeFileName
import java.io.File

/**
 * MIME real del fichero a abrir. Antes estaba fijado a tipo audio (ver
 * nota histórica: era el comodín audio asterisco-barra), así que un
 * MP4 se anunciaba como audio y el reproductor lo trataba como canción
 * (icono de música, sin superficie de vídeo). Primero se pregunta al
 * MediaStore (la fila guarda el mime correcto) y solo se recurre a la
 * extensión si eso falla.
 */
private fun mimeForPath(app: android.content.Context, uri: Uri, path: String): String {
    if (uri.scheme == "content") {
        try {
            app.contentResolver.getType(uri)?.let { return it }
        } catch (_: Exception) {}
    }
    return when {
        path.endsWith(".mp4", true) || path.endsWith(".m4v", true) -> "video/mp4"
        path.endsWith(".webm", true) -> "video/webm"
        path.endsWith(".mkv", true) -> "video/x-matroska"
        path.endsWith(".mp3", true) -> "audio/mpeg"
        path.endsWith(".m4a", true) || path.endsWith(".m4b", true) -> "audio/mp4"
        path.endsWith(".wav", true) -> "audio/wav"
        path.endsWith(".ogg", true) || path.endsWith(".opus", true) -> "audio/ogg"
        path.endsWith(".flac", true) -> "audio/flac"
        else -> "audio/*"
    }
}

actual fun openInFileManager(path: String) {
    try {
        val app = com.mp3downloader.Mp3DownloaderApp.instance
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) return
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForPath(app, uri, path))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Concede lectura temporal al receptor: sin esto, apps conjenas
            // pueden recibir un content:// que no pueden abrir.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(intent)
    } catch (_: Exception) {}
}

actual fun saveToPublicDownloads(sourcePath: String, fileName: String): String? {
    return try {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        val app = com.mp3downloader.Mp3DownloaderApp.instance

        val safeName = sanitizeFileName(fileName)

        // Audio y vídeo se separan en carpetas distintas y cada fichero se
        // declara con su MIME real: un MP4 es video/mp4 y vive en
        // Download/Videos/, nunca se cuela como audio en Download/Audio/.
        // Antes el mapa MIME no contemplaba vídeo y caía en un comodín que
        // ni siquiera es un MIME válido, así que la separación la decidía la
        // extensión por casualidad.
        val lowerName = fileName.lowercase()
        val isVideoFile = lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v") ||
            lowerName.endsWith(".webm") || lowerName.endsWith(".mkv")
        val mime = when {
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".m4a") || lowerName.endsWith(".m4b") -> "audio/mp4"
            lowerName.endsWith(".wav") -> "audio/wav"
            lowerName.endsWith(".ogg") || lowerName.endsWith(".opus") -> "audio/ogg"
            lowerName.endsWith(".flac") -> "audio/flac"
            lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v") -> "video/mp4"
            lowerName.endsWith(".webm") -> "video/webm"
            lowerName.endsWith(".mkv") -> "video/x-matroska"
            // Formato desconocido: binario genérico en vez de mentir con
            // audio/* (el resto de apps lo trataría como música).
            else -> "application/octet-stream"
        }
        val subDir = if (isVideoFile) "Videos" else "Audio"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                // Subcarpeta bajo Download/: ahí acaban separados audio y
                // vídeo en lugar de mezclados en la raíz.
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$subDir/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                app.contentResolver.update(uri, values, null, null)
                return uri.toString()
            }
        } else {
            // Mismo criterio de carpetas en Android 9 y anteriores.
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                subDir
            )
            dir.mkdirs()
            val dest = File(dir, safeName)
            sourceFile.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        null
    } catch (_: Exception) { null }
}

actual fun copyTextToClipboard(text: String) {
    try {
        val app = com.mp3downloader.Mp3DownloaderApp.instance
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("error", text)
        clipboard.setPrimaryClip(clip)
    } catch (_: Exception) {}
}


