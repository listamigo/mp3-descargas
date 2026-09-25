package com.mp3downloader.domain.service

import java.util.logging.Level
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("mp3downloader").apply {
    useParentHandlers = true
    level = Level.INFO
}

actual object AppLog {
    actual fun d(tag: String, message: String) {
        logger.log(Level.FINE, "[$tag] $message")
    }

    actual fun w(tag: String, message: String) {
        logger.log(Level.WARNING, "[$tag] $message")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        logger.log(Level.SEVERE, "[$tag] $message", throwable)
    }
}
