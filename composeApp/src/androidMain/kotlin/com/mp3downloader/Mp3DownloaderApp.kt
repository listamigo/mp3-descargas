package com.mp3downloader

import android.app.Application
import com.mp3downloader.data.storage.AndroidStorage
import com.mp3downloader.di.sharedModules
import org.koin.core.context.startKoin
import java.io.File

class Mp3DownloaderApp : Application() {
    companion object {
        lateinit var instance: Mp3DownloaderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AndroidStorage.basePath = filesDir.absolutePath + "/.mp3downloader"
        AndroidStorage.downloadPath = "${filesDir.absolutePath}/Downloads"
        File(AndroidStorage.downloadPath).mkdirs()
        startKoin {
            modules(sharedModules)
        }
    }
}
