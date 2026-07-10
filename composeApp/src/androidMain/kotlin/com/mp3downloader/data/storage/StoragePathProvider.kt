package com.mp3downloader.data.storage

object AndroidStorage {
    lateinit var basePath: String
    lateinit var downloadPath: String
}

actual fun provideStorageDirectory(): String {
    return AndroidStorage.basePath
}

actual fun provideDownloadDirectory(): String {
    return AndroidStorage.downloadPath
}
