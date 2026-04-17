package com.elishaazaria.sayboard.data

/**
 * Simple locale representation for KMP.
 * Replaces java.util.Locale in the shared module.
 */
data class SpeakKeysLocale(val language: String, val country: String = "", val variant: String = "") {
    companion object {
        val ROOT = SpeakKeysLocale("")
    }
}
