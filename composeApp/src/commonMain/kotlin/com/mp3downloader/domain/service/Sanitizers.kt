package com.mp3downloader.domain.service

/**
 * Pure, platform-agnostic input sanitization helpers used as a first line of
 * defense before any value reaches the network layer or the filesystem.
 */

private val YT_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

/** YouTube video IDs are exactly 11 chars from this alphabet. */
fun isValidYouTubeId(id: String): Boolean = YT_ID_REGEX.matches(id)

/**
 * Clean a user search query: trim, drop control characters, and cap length so
 * a hostile/garbage input cannot be forwarded verbatim to the backend.
 */
fun sanitizeSearchQuery(raw: String, maxLen: Int = 100): String {
    val trimmed = raw.trim()
    val cleaned = trimmed.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
    return cleaned.take(maxLen).trim()
}

/**
 * Turn an arbitrary title into a safe single-path filename component. Removes
 * illegal chars, collapses/trims dots (prevents ".." tricks) and falls back to
 * a default when empty.
 */
fun sanitizeFileName(name: String, maxLen: Int = 120, fallback: String = "audio"): String {
    val replaced = name
        .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .replace(Regex("\\.{2,}"), "_")
        .trim('.', ' ', '_')
    val final = if (replaced.isBlank()) fallback else replaced
    return final.take(maxLen)
}
