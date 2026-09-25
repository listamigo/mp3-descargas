package com.mp3downloader.domain.service

/**
 * Log multiplataforma para código `commonMain`.
 *
 * Sustituye a los `println` sueltos, que en Android escriben en stdout sin
 * nivel, sin etiqueta y fuera de Logcat, impidiendo filtrar el diagnóstico de
 * la cadena de motores de descarga.
 *
 * - Android: `android.util.Log` (visible en Logcat bajo la etiqueta dada).
 * - Desktop: `java.util.logging` (stdout/stderr de la JVM).
 */
expect object AppLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
