package com.mp3downloader.domain.service

import java.awt.Desktop
import java.io.File

actual fun openInFileManager(path: String) {
    try {
        val file = File(path)
        if (file.exists()) {
            Desktop.getDesktop().open(file.parentFile)
        }
    } catch (_: Exception) {}
}

actual fun saveToPublicDownloads(sourcePath: String, fileName: String): String? {
    return try {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        val destDir = File(System.getProperty("user.home"), "Download")
        destDir.mkdirs()
        val dest = File(destDir, fileName)
        sourceFile.copyTo(dest, overwrite = true)
        dest.absolutePath
    } catch (_: Exception) { null }
}
