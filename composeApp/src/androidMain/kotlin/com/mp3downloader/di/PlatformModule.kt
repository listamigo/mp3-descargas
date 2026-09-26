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
        // Un motor por rol, en orden: si el principal cae, FallbackEngine pasa
        // al siguiente. Render va segundo porque su arranque en frio es mas
        // lento, pero es el que sobrevive a que Railway se quede sin credito.
        //
        // El host se pasa como provider y no como String porque Koin construye
        // esta cadena una sola vez por proceso: con la URL capturada aqui, un
        // cambio en Ajustes no llegaba a los motores hasta reiniciar la app.
        val remote = listOf(
            RemoteServerEngine { RemoteConfig.serverUrl },
            RemoteServerEngine { RemoteConfig.fallbackServerUrl }
        )
        val invidious = InvidiousApiEngine()
        val engines = mutableListOf<DownloadEngine>()
        // Orden: los servidores remotos van primero y en prioridad, Render
        // justo detrás del principal. Invidious y Piped son el último recurso
        // porque su audio no se puede descargar (el companion de f5.si devuelve
        // error), así que intercalarlos entre los dos servidores los dejaría
        // respondiendo la búsqueda y rompiendo la descarga.
        engines.addAll(remote)
        engines.add(invidious)
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
            // Con el servidor despierto los remotos ya van primero por orden de
            // declaración, así que no hay nada que adelantar. Mientras está
            // dormido se invierte: Invidious contesta la búsqueda en segundos y
            // la descarga cae sola al remoto cuando llega el momento.
            preferredForSearch = { if (RemoteHealth.isWarm) emptyList() else listOf(invidious) }
        )
    }
    single { AudioPreviewer() }
}
