package com.elishaazaria.sayboard.recognition.recognizers.sources

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.logging.Logger
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WhisperCloud(
    private val apiKey: String,
    private val displayLocale: SpeakKeysLocale,
    private val prompt: String = "",
    private val transliterateToRoman: Boolean = false
) : RecognizerSource {

    companion object {
        private const val TAG = "WhisperCloud"
    }

    private val _stateFlow = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = _stateFlow.asStateFlow()

    private var myRecognizer: WhisperCloudRecognizer? = null

    override val recognizer: Recognizer get() = myRecognizer!!

    override val addSpaces: Boolean
        get() = !listOf("ja", "zh").contains(displayLocale.language)

    override val isBatchRecognizer: Boolean = true

    override val closed: Boolean get() = myRecognizer == null

    override suspend fun initialize() {
        Logger.d(TAG, "initialize() called, current closed state: $closed")
        _stateFlow.value = RecognizerState.LOADING

        val isValidKey = apiKey.isNotEmpty()
        Logger.d(TAG, "isValidKey: $isValidKey")

        if (isValidKey) {
            val langCode = displayLocale.language.takeIf { it.isNotEmpty() }
            Logger.d(TAG, "Creating WhisperCloudRecognizer with prompt: $prompt, transliterate: $transliterateToRoman")
            myRecognizer = WhisperCloudRecognizer(apiKey, langCode, prompt, transliterateToRoman)
            _stateFlow.value = RecognizerState.READY
            Logger.d(TAG, "Recognizer created, closed state now: $closed")
        } else {
            Logger.e(TAG, "Invalid API key!")
            _stateFlow.value = RecognizerState.ERROR
        }
    }

    override fun close(freeRAM: Boolean) {
        if (freeRAM) {
            myRecognizer = null
        }
    }

    override val errorMessage: String get() = "Invalid or missing API key"
    override val name: String get() = "Whisper Cloud (OpenAI)"
    override val locale: SpeakKeysLocale get() = displayLocale
}
