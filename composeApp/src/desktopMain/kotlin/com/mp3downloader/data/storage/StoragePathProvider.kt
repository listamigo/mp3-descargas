package com.mp3downloader.data.storage

actual fun provideStorageDirectory(): String {
    return System.getProperty("user.home") + "/.mp3downloader"
}

actual fun provideDownloadDirectory(): String {
    val home = System.getProperty("user.home") ?: "/tmp"
    return "$home/Download"
}
