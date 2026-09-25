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

    /** Probes the configured host. Serialised, so overlapping callers share one request. */
    suspend fun refresh(): Boolean = mutex.withLock {
        val server = RemoteConfig.serverUrl
        if (server.isNullOrBlank()) return false
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val response: HttpResponse = client.get("$server/api/health")
                response.status.value in 200..299
            }.getOrElse {
                AppLog.w(TAG, "health probe failed: ${it.message}")
                false
            }
        }
        lastOkAt = if (ok) System.currentTimeMillis() else 0L
        if (ok) AppLog.d(TAG, "server warm: $server")
        ok
    }
}
