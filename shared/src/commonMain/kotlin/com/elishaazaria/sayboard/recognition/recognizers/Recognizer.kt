package com.elishaazaria.sayboard.recognition.recognizers

interface Recognizer {
    fun reset()
    fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean
    fun getResult(): String
    fun getPartialResult(): String
    fun getFinalResult(): String
    val sampleRate: Float
    val languageCode: String?

    val localeNeedsRemovingSpace: Boolean
        get() = languageCode?.let { listOf("ja", "zh").contains(it) } ?: false

    fun removeSpaceForLocale(text: String): String {
        return if (localeNeedsRemovingSpace) text.replace("\\s".toRegex(), "")
        else text
    }
}
