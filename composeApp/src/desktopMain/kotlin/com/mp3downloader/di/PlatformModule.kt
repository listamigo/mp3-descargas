package com.mp3downloader.di

import com.mp3downloader.data.engine.DownloadEngine
import com.mp3downloader.data.engine.YtDlpProcessEngine
import com.mp3downloader.domain.service.AudioPreviewer
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<DownloadEngine> { YtDlpProcessEngine() }
    single { AudioPreviewer() }
}
