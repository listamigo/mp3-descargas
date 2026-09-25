package com.mp3downloader.domain.service

actual object AppLog {
    actual fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    actual fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) android.util.Log.e(tag, message, throwable)
        else android.util.Log.e(tag, message)
    }
}
