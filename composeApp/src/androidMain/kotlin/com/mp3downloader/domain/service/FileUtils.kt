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
import java.io.File

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
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    } catch (_: Exception) {}
}

actual fun saveToPublicDownloads(sourcePath: String, fileName: String): String? {
    return try {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        val app = com.mp3downloader.Mp3DownloaderApp.instance

        val mime = when {
            fileName.endsWith(".mp3") -> "audio/mpeg"
            fileName.endsWith(".m4a") || fileName.endsWith(".m4b") -> "audio/mp4"
            fileName.endsWith(".wav") -> "audio/wav"
            fileName.endsWith(".ogg") -> "audio/ogg"
            fileName.endsWith(".flac") -> "audio/flac"
            else -> "audio/*"
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
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
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, fileName)
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


