package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import helium314.keyboard.voice.streaming.RealtimeJson.boolean
import helium314.keyboard.voice.streaming.RealtimeJson.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Sarvam Saaras v3 Realtime with manual push-to-talk endpointing. */
internal class SarvamRealtimeRecognizer(
    apiKey: String,
    override val languageCode: String?,
    sarvamLanguageCode: String = "auto",
    mode: String = "codemix",
    streamType: String = "balanced",
    prompt: String = "",
    client: OkHttpClient = RealtimeHttpClient.instance,
    endpoint: String = SARVAM_REALTIME_ENDPOINT,
    finalResultTimeoutMillis: Long = DEFAULT_FINAL_TIMEOUT_MILLIS,
    maxPreOpenAudioMillis: Int = DEFAULT_PRE_OPEN_AUDIO_MILLIS,
) : RealtimeWebSocketRecognizer(
    client = client,
    protocol = SarvamRealtimeProtocol(
        apiKey = apiKey,
        languageCode = sarvamLanguageCode,
        mode = mode,
        streamType = streamType,
        prompt = prompt,
        endpoint = endpoint,
    ),
    finalResultTimeoutMillis = finalResultTimeoutMillis,
    maxPreOpenAudioMillis = maxPreOpenAudioMillis,
) {
    companion object {
        const val SARVAM_REALTIME_ENDPOINT = "wss://api.sarvam.ai/speech-to-text-realtime/ws"
        const val DEFAULT_FINAL_TIMEOUT_MILLIS = 4_000L
        const val DEFAULT_PRE_OPEN_AUDIO_MILLIS = 3_000
    }
}

/**
 * Android realtime replacement for the shared module's batch Sarvam source.
 *
 * The display locale controls model selection in SpeakKeys. [sarvamLanguageCode] controls the
 * server hint; `auto` is the safest Hinglish default, while [mode] `codemix` preserves both scripts.
 */
class SarvamRealtimeRecognizerSource(
    private val apiKey: String,
    private val displayLocale: SpeakKeysLocale,
    private val sarvamLanguageCode: String = "auto",
    private val mode: String = "codemix",
    private val streamType: String = "balanced",
    private val prompt: String = "",
    private val client: OkHttpClient = RealtimeHttpClient.instance,
    private val endpoint: String = SarvamRealtimeRecognizer.SARVAM_REALTIME_ENDPOINT,
    private val finalResultTimeoutMillis: Long = SarvamRealtimeRecognizer.DEFAULT_FINAL_TIMEOUT_MILLIS,
    private val maxPreOpenAudioMillis: Int = SarvamRealtimeRecognizer.DEFAULT_PRE_OPEN_AUDIO_MILLIS,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) : RecognizerSource {
    private val mutableState = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = mutableState.asStateFlow()

    private var delegate: SarvamRealtimeRecognizer? = null
    @Volatile private var initializationError: String? = null

    override val recognizer: Recognizer
        get() = checkNotNull(delegate) { "Sarvam realtime source has not been initialized" }

    override val addSpaces: Boolean = true
    override val isBatchRecognizer: Boolean = false
    override val closed: Boolean get() = delegate == null
    override val errorMessage: String
        get() = initializationError ?: "Add a Sarvam API key to use Fast Hinglish"
    override val name: String get() = "Fast Hinglish"
    override val locale: SpeakKeysLocale get() = displayLocale

    override suspend fun initialize() {
        mutableState.value = RecognizerState.LOADING
        if (apiKey.isBlank()) {
            delegate = null
            initializationError = "Add a Sarvam API key to use Fast Hinglish"
            mutableState.value = RecognizerState.ERROR
            return
        }

        delegate?.cancelCurrentUtterance()
        delegate = null
        initializationError = null
        val candidate = try {
            SarvamRealtimeRecognizer(
                apiKey = apiKey,
                languageCode = displayLocale.language.takeIf { it.isNotBlank() },
                sarvamLanguageCode = SarvamRealtimeProtocol.normalizeLanguageCode(sarvamLanguageCode),
                mode = normalizeMode(mode),
                streamType = streamType,
                prompt = prompt,
                client = client,
                endpoint = endpoint,
                finalResultTimeoutMillis = finalResultTimeoutMillis,
                maxPreOpenAudioMillis = maxPreOpenAudioMillis,
            )
        } catch (error: Exception) {
            initializationError = error.message ?: "Sarvam realtime configuration is invalid"
            mutableState.value = RecognizerState.ERROR
            return
        }
        delegate = candidate
        try {
            candidate.prepareAndAwaitReady(handshakeTimeoutMillis)
            if (delegate === candidate) mutableState.value = RecognizerState.READY
        } catch (error: Exception) {
            candidate.cancelCurrentUtterance()
            if (delegate === candidate) {
                delegate = null
                initializationError = error.message ?: "Sarvam realtime connection failed"
                mutableState.value = RecognizerState.ERROR
            }
        }
    }

    override fun close(freeRAM: Boolean) {
        if (freeRAM) {
            delegate?.cancelCurrentUtterance()
        } else {
            // Dispose an unused prewarm, but never let delayed source cleanup cancel a session
            // already claimed by a rapid next mic press.
            delegate?.cleanupAfterUtteranceOrIdle()
        }
        if (freeRAM) {
            delegate = null
            mutableState.value = RecognizerState.CLOSED
        }
    }

    private fun normalizeMode(value: String): String = when (value.trim().lowercase()) {
        "native" -> "transcribe"
        "mixed" -> "codemix"
        else -> value.trim().lowercase()
    }

    private companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5_000L
    }

}

internal class SarvamRealtimeProtocol(
    private val apiKey: String,
    private val languageCode: String,
    private val mode: String,
    private val streamType: String,
    private val prompt: String,
    private val endpoint: String,
) : RealtimeSpeechProtocol {
    init {
        require(apiKey.isNotBlank()) { "Sarvam API key cannot be blank" }
        require(languageCode in SUPPORTED_LANGUAGE_CODES) {
            "Unsupported Sarvam realtime language code: $languageCode"
        }
        require(mode in SUPPORTED_MODES) { "Unsupported Sarvam mode: $mode" }
        require(streamType in SUPPORTED_STREAM_TYPES) { "Unsupported Sarvam stream type: $streamType" }
    }

    override val providerName: String = "Sarvam"
    override val holdLastAudioChunkForCommit: Boolean = false

    override fun request(): Request {
        val url = websocketUrl(endpoint).newBuilder()
            .addQueryParameter("language_code", languageCode)
            .addQueryParameter("model", "saaras:v3-realtime")
            .addQueryParameter("stream_type", streamType)
            .addQueryParameter("mode", mode)
            .addQueryParameter("endpointing", "manual")
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("sample_rate", "16000")
            .apply { if (prompt.isNotBlank()) addQueryParameter("prompt", prompt) }
            .build()

        return Request.Builder()
            .url(url)
            .header("API-SUBSCRIPTION-KEY", apiKey)
            .build()
    }

    override fun sessionStartMessages(): List<String> = listOf("{\"event\":\"speech_start\"}")

    override fun audioMessage(pcmLittleEndian: ByteArray, commit: Boolean): String =
        "{\"event\":\"audio_input\",\"audio\":${RealtimeJson.quote(RealtimeJson.audioBase64(pcmLittleEndian))}}"

    // With endpointing=manual, speech_end is the documented utterance finalizer. The separate
    // `flush` event is an alternative force-finalizer, not an additional message to send after
    // speech_end; sending both can create a second empty finalization boundary.
    override fun finishMessages(): List<String> = listOf("{\"event\":\"speech_end\"}")

    override fun parseServerMessage(message: String): RealtimeServerEvent {
        val json = RealtimeJson.objectOrNull(message)
            ?: return RealtimeServerEvent.Error("Malformed server message", fatal = false)
        val event = json.string("event")?.lowercase().orEmpty()

        // Keep this deliberately strict to the dedicated Saaras v3 Realtime AsyncAPI. The legacy
        // `/speech-to-text/ws` endpoint has different `type/data` envelopes and must not silently
        // influence readiness or finalization on this connection.
        return when (event) {
            "session.begin" -> RealtimeServerEvent.Ready
            "transcript.partial" -> RealtimeServerEvent.Partial(
                RealtimeJson.primitive(json["text"]).orEmpty(),
            )
            "transcript.final" -> RealtimeServerEvent.Final(
                RealtimeJson.primitive(json["text"]).orEmpty(),
            )
            "error" -> RealtimeServerEvent.Error(
                json.string("message") ?: "Unknown Sarvam realtime error",
                fatal = json.boolean("is_fatal") ?: true,
            )
            else -> RealtimeServerEvent.Ignored
        }
    }

    companion object {
        val SUPPORTED_MODES = setOf("transcribe", "translate", "verbatim", "translit", "codemix")
        val SUPPORTED_STREAM_TYPES = setOf("fast", "balanced", "simulated")
        private val SUPPORTED_LANGUAGE_CODES = setOf(
            "auto", "en-IN", "hi-IN", "bn-IN", "kn-IN", "ml-IN", "mr-IN", "or-IN",
            "pa-IN", "ta-IN", "te-IN", "gu-IN", "as-IN", "ur-IN", "ne-IN", "kok-IN",
            "ks-IN", "sd-IN", "sa-IN", "sat-IN", "mni-IN", "brx-IN", "mai-IN", "doi-IN",
        )

        internal fun normalizeLanguageCode(value: String): String {
            val trimmed = value.trim()
            val lower = trimmed.lowercase()
            if (lower.isEmpty() || lower == "unknown" || lower == "und") return "auto"
            if (lower == "od-in") return "or-IN" // legacy batch preference spelling
            return SUPPORTED_LANGUAGE_CODES.firstOrNull { it.equals(trimmed, ignoreCase = true) }
                ?: trimmed
        }
    }
}

internal object RealtimeHttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun websocketUrl(endpoint: String): HttpUrl {
    val httpEndpoint = when {
        endpoint.startsWith("wss://", ignoreCase = true) -> "https://${endpoint.substring(6)}"
        endpoint.startsWith("ws://", ignoreCase = true) -> "http://${endpoint.substring(5)}"
        else -> endpoint
    }
    return httpEndpoint.toHttpUrl()
}
