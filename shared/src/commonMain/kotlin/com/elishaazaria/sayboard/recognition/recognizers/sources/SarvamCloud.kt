package com.elishaazaria.sayboard.recognition.recognizers.sources

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.logging.Logger
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SarvamCloud(
    private val apiKey: String,
    private val displayLocale: SpeakKeysLocale,
    private val mode: String = "translit",
    private val languageCode: String = "unknown"
) : RecognizerSource {

    companion object {
        private const val TAG = "SarvamCloud"
    }

    private val _stateFlow = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = _stateFlow.asStateFlow()

    private var myRecognizer: SarvamCloudRecognizer? = null

    override val recognizer: Recognizer get() = myRecognizer!!

    override val addSpaces: Boolean get() = true

    override val isBatchRecognizer: Boolean = true

    override val closed: Boolean get() = myRecognizer == null

    override suspend fun initialize() {
        Logger.d(TAG, "initialize() called, current closed state: $closed")
        _stateFlow.value = RecognizerState.LOADING

        val isValidKey = apiKey.isNotEmpty()
        Logger.d(TAG, "isValidKey: $isValidKey")

        if (isValidKey) {
            Logger.d(TAG, "Creating SarvamCloudRecognizer")
            myRecognizer = SarvamCloudRecognizer(apiKey, displayLocale.language, mode, languageCode)
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

    override val errorMessage: String get() = "Missing Sarvam API subscription key"
    override val name: String get() = "Sarvam Cloud (Hinglish)"
    override val locale: SpeakKeysLocale get() = displayLocale
}
