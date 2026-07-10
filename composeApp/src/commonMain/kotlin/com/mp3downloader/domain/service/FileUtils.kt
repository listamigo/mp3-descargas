package com.mp3downloader.domain.service

expect fun openInFileManager(path: String)

expect fun saveToPublicDownloads(sourcePath: String, fileName: String): String?

expect fun copyTextToClipboard(text: String)

