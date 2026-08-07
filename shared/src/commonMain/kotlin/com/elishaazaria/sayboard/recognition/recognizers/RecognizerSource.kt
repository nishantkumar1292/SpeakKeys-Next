package com.elishaazaria.sayboard.recognition.recognizers

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import kotlinx.coroutines.flow.StateFlow

interface RecognizerSource {
    suspend fun initialize()
    val recognizer: Recognizer
    fun close(freeRAM: Boolean)
    val stateFlow: StateFlow<RecognizerState>

    val addSpaces: Boolean

    /**
     * If true, this recognizer processes audio in batch (like Whisper).
     * Tapping mic to "pause" should actually stop recording to trigger transcription.
     * If false, this recognizer streams results in real-time.
     */
    val isBatchRecognizer: Boolean
        get() = false

    val closed: Boolean

    val errorMessage: String
    val name: String

    val locale: SpeakKeysLocale
}

/**
 * Opt-in marker for an initialization failure that may recover after authentication refresh.
 * Generic model, configuration, and runtime errors must not trigger an automatic retry loop.
 */
interface RecoverableAuthFailureSource {
    val hasRecoverableAuthFailure: Boolean
}
