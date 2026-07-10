package com.mp3downloader.di

import com.mp3downloader.data.engine.DownloadEngine
import com.mp3downloader.data.engine.FallbackEngine
import com.mp3downloader.data.engine.InvidiousApiEngine
import com.mp3downloader.data.engine.PipedApiEngine
import com.mp3downloader.data.engine.RemoteConfig
import com.mp3downloader.data.engine.RemoteServerEngine
import com.mp3downloader.domain.service.AudioPreviewer
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<DownloadEngine> {
        val engines = mutableListOf<DownloadEngine>()
        if (RemoteConfig.serverUrl != null) {
            engines.add(RemoteServerEngine())
        }
        engines.add(InvidiousApiEngine())
        engines.add(PipedApiEngine())
        FallbackEngine(engines)
    }
    single { AudioPreviewer() }
}
