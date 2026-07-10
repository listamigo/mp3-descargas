package com.mp3downloader.di

import com.mp3downloader.data.repository.FileHistoryRepository
import com.mp3downloader.data.repository.HistoryRepository
import com.mp3downloader.data.repository.SongRepositoryImpl
import com.mp3downloader.data.storage.provideStorageDirectory
import com.mp3downloader.domain.repository.SongRepository
import com.mp3downloader.ui.MainViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

val appModule: Module = module {
    single<SongRepository> { SongRepositoryImpl(get()) }
    single<HistoryRepository> { FileHistoryRepository(provideStorageDirectory()) }
    factory { MainViewModel(get(), get(), get()) }
}

val sharedModules = listOf(platformModule, appModule)
