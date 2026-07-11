package com.mp3downloader.domain.service

import java.net.InetAddress
import java.net.URI

/**
 * Android-side URL safety checks. Used to block Server-Side Request Forgery
 * (SSRF): the app talks to third-party instances (Invidious/Piped) and to a
 * user-configured server, all of which can return attacker-influenced URLs
 * (thumbnails, audio streams, cover art). We only accept `https` targets that
 * do not resolve to private/loopback/link-local addresses.
 */

private const val MAX_URL_LENGTH = 2048

/**
 * Returns true only if [url] is a well-formed `https` URL whose host is not a
 * private/loopback/link-local address. DNS resolution is attempted so that
 * literal or resolvable internal IPs are rejected.
 */
fun isSafeHttpsUrl(url: String?): Boolean {
    if (url == null || url.length > MAX_URL_LENGTH) return false
    return try {
        val uri = URI(url)
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        if (host.equals("localhost", ignoreCase = true)) return false

        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (address != null) {
            if (address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                address.isAnyLocalAddress
            ) {
                return false
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
