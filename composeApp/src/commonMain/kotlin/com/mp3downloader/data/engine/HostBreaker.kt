package com.mp3downloader.data.engine

import com.mp3downloader.domain.service.AppLog

/**
 * Per-host circuit breaker.
 *
 * Render exists because Railway is not always usable from a datacenter IP: on
 * a fresh video it walks every yt-dlp client, then the proxy, and only then
 * gives up with 502, measured at 71-87 s. The chain already falls through to
 * Render, so the download does finish, but the user watches a dead progress
 * bar for over a minute first. This remembers that and skips the dead host.
 *
 * Only outright failures trip it (5xx, timeouts, dead DNS, a 200 that carried
 * an error body). Being slow is not evidence of being down, and a successful
 * request clears the memory immediately, so a host that recovers is used
 * again at once.
 *
 * State is in memory only, on purpose: it is a hint about the last few
 * minutes, and persisting it would mean booting the app into a chain that
 * avoids a host that has been fixed for hours.
 */
object HostBreaker {
    private const val TAG = "HostBreaker"
    private const val WINDOW_MS = 3 * 60 * 1000L
    private const val MIN_FAILURES = 2

    private class Entry {
        var failures = 0
        var firstAt = 0L
        var lastDetail = ""
    }

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun reportFailure(host: String?, detail: String) {
        if (host.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val entry = entries.getOrPut(host) { Entry() }
        if (now - entry.firstAt > WINDOW_MS) {
            entry.firstAt = now
            entry.failures = 0
        }
        entry.failures++
        entry.lastDetail = detail
        if (entry.failures >= MIN_FAILURES) {
            AppLog.w(TAG, "host '$host' marcado como caido ($detail), se usara el respaldo")
        }
    }

    @Synchronized
    fun reportSuccess(host: String?) {
        if (host.isNullOrBlank()) return
        if (entries.remove(host) != null) {
            AppLog.d(TAG, "host '$host' volvio a responder, cortacircuitos despejado")
        }
    }

    @Synchronized
    fun isTripped(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val entry = entries[host] ?: return false
        if (System.currentTimeMillis() - entry.firstAt > WINDOW_MS) {
            entries.remove(host)
            return false
        }
        return entry.failures >= MIN_FAILURES
    }

    @Synchronized
    fun reset() = entries.clear()
}
