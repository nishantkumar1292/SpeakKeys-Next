package com.elishaazaria.sayboard.recognition.recognizers

interface Recognizer {
    fun reset()
    fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean
    fun getResult(): String
    fun getPartialResult(): String
    fun getFinalResult(): String

    /**
     * Abandons the current utterance without producing a final transcript.
     *
     * Batch recognizers historically had no distinct cancellation path. Their
     * finalization is skipped and the next utterance starts with [reset], so the
     * default is deliberately a no-op; this avoids racing a native recognizer's
     * audio callback. Network and streaming implementations must override this
     * to close the request before buffered audio is uploaded.
     */
    fun cancel() = Unit
    val sampleRate: Float
    val languageCode: String?

    val localeNeedsRemovingSpace: Boolean
        get() = languageCode?.let { listOf("ja", "zh").contains(it) } ?: false

    fun removeSpaceForLocale(text: String): String {
        return if (localeNeedsRemovingSpace) text.replace("\\s".toRegex(), "")
        else text
    }
}
