package com.elishaazaria.sayboard.recognition.logging

import platform.Foundation.NSLog

actual object Logger {
    actual fun d(tag: String, message: String) {
        NSLog("D/%s: %s", tag, message)
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val suffix = throwable?.message?.let { " | $it" } ?: ""
        NSLog("E/%s: %s%s", tag, message, suffix)
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        val suffix = throwable?.message?.let { " | $it" } ?: ""
        NSLog("W/%s: %s%s", tag, message, suffix)
    }
}
