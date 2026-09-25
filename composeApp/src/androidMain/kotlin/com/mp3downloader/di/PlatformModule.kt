package com.mp3downloader.di

import com.mp3downloader.data.engine.DownloadEngine
import com.mp3downloader.data.engine.FallbackEngine
import com.mp3downloader.data.engine.InvidiousApiEngine
import com.mp3downloader.data.engine.PipedApiEngine
import com.mp3downloader.data.engine.RemoteConfig
import com.mp3downloader.data.engine.RemoteServerEngine
import com.mp3downloader.domain.service.AudioPreviewer
import com.mp3downloader.domain.service.RemoteHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<DownloadEngine> {
        val remote = RemoteServerEngine()
        val engines = mutableListOf<DownloadEngine>()
        // Invidious primero: es la única vía gratuita que funciona sin
        // servidor propio (instancia verificada por defecto en el engine).
        // El servidor propio queda como segunda opción y, cuando ya está
        // despierto, adelanta a Invidious en las búsquedas porque devuelve el
        // nombre real del artista en vez del canal del que extrajo el título.
        // Piped solo entra si el usuario indica una instancia viva.
        engines.add(InvidiousApiEngine())
        engines.add(remote)
        engines.add(PipedApiEngine())
        FallbackEngine(
            engines = engines,
            // El hosting gratuito se duerme a los ~15 min: se despierta en
            // segundo plano mientras el usuario escribe, sin retrasar la
            // búsqueda ni encadenar dos sondas simultáneas.
            beforeSearch = {
                if (!RemoteHealth.isWarm) {
                    CoroutineScope(currentCoroutineContext()).launch { RemoteHealth.refresh() }
                }
            },
            beforeDownload = {
                if (!RemoteHealth.isWarm) {
                    CoroutineScope(currentCoroutineContext()).launch { RemoteHealth.refresh() }
                }
            },
            preferredForSearch = { if (RemoteHealth.isWarm) listOf(remote) else emptyList() }
        )
    }
    single { AudioPreviewer() }
}
