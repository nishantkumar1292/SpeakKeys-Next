package com.elishaazaria.sayboard.recognition.recognizers.sources

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.logging.Logger
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RecognizerSource that uses the proxied cloud endpoint.
 * Supports both Whisper and Sarvam via the proxy, selected by [provider].
 */
class ProxiedCloud(
    private val authTokenProvider: AuthTokenProvider,
    private val provider: String,
    private val displayLocale: SpeakKeysLocale,
    private val providerParams: Map<String, String> = emptyMap(),
    private val transliterateToRoman: Boolean = false
) : RecognizerSource {

    companion object {
        private const val TAG = "ProxiedCloud"
    }

    private val _stateFlow = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = _stateFlow.asStateFlow()

    private var myRecognizer: ProxiedCloudRecognizer? = null

    override val recognizer: Recognizer get() = myRecognizer!!

    override val addSpaces: Boolean
        get() = !listOf("ja", "zh").contains(displayLocale.language)

    override val isBatchRecognizer: Boolean = true

    override val closed: Boolean get() = myRecognizer == null

    override suspend fun initialize() {
        Logger.d(TAG, "initialize() for provider=$provider")
        _stateFlow.value = RecognizerState.LOADING

        if (!authTokenProvider.isSignedIn) {
            Logger.e(TAG, "Not signed in!")
            _stateFlow.value = RecognizerState.ERROR
            return
        }

        myRecognizer = ProxiedCloudRecognizer(
            tokenProvider = { authTokenProvider.getIdToken() },
            provider = provider,
            languageCode = displayLocale.language,
            providerParams = providerParams,
            transliterateToRoman = transliterateToRoman
        )
        _stateFlow.value = RecognizerState.READY
    }

    override fun close(freeRAM: Boolean) {
        if (freeRAM) {
            myRecognizer = null
        }
    }

    override val errorMessage: String get() = "Session expired. Retrying\u2026"
    override val name: String
        get() = when (provider) {
            "sarvam" -> "Sarvam Cloud (Proxied)"
            else -> "Whisper Cloud (Proxied)"
        }

    override val locale: SpeakKeysLocale get() = displayLocale
}
