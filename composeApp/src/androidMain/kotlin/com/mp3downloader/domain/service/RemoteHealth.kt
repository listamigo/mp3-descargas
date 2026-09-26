package com.mp3downloader.domain.service

import com.mp3downloader.data.engine.RemoteConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Free hosting spins its container down after ~15 min without traffic and needs
 * 30-50 s to boot again. Probing /api/health (622 bytes) while the user is
 * still typing a query moves that cost off the first download, which otherwise
 * sits on a frozen 0% for over a minute while the server wakes up.
 *
 * A warm server is also allowed to answer searches first: it returns real
 * artist names, where Invidious reports whatever channel name it scraped.
 */
object RemoteHealth {
    private const val TAG = "RemoteHealth"
    private const val WARM_TTL_MS = 10 * 60 * 1000L

    private val client = HttpClient {
        install(HttpTimeout) {
            // A cold container does not answer quickly, so these are generous on
            // purpose; the probe always runs off the UI path.
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 60_000
            socketTimeoutMillis = 120_000
        }
    }

    private val mutex = Mutex()
    private var lastOkAt = 0L

    /** True when a recent probe succeeded, so the container is already booted. */
    val isWarm: Boolean
        get() = lastOkAt > 0L && System.currentTimeMillis() - lastOkAt < WARM_TTL_MS

    /**
     * Probes the configured hosts, primary first, stopping at the first that
     * answers. Serialised, so overlapping callers share one request.
     *
     * Falls through to the second host on purpose. If it only ever probed the
     * primary, then a Railway outage would leave [isWarm] false, which makes
     * the engine chain try the dead Invidious instance first and burn its
     * ~25 s timeout before reaching the host that actually works.
     */
    suspend fun refresh(): Boolean = mutex.withLock {
        var ok = false
        var warmed: String? = null
        for (server in RemoteConfig.remoteServerUrls) {
            val probeOk = withContext(Dispatchers.IO) {
                runCatching {
                    val response: HttpResponse = client.get("$server/api/health")
                    response.status.value in 200..299
                }.getOrElse {
                    AppLog.w(TAG, "health probe failed for $server: ${it.message}")
                    false
                }
            }
            if (probeOk) {
                ok = true
                warmed = server
                break
            }
        }
        lastOkAt = if (ok) System.currentTimeMillis() else 0L
        if (ok) AppLog.d(TAG, "server warm: $warmed")
        ok
    }
}
