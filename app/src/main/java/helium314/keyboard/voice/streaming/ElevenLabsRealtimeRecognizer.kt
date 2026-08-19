package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import helium314.keyboard.voice.streaming.RealtimeJson.nestedObject
import helium314.keyboard.voice.streaming.RealtimeJson.string
import helium314.keyboard.voice.streaming.RealtimeJson.transcriptText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface ElevenLabsRealtimeAuth {
    /** A user-owned key can authenticate every WebSocket opened by this source. */
    data class ApiKey(val value: String) : ElevenLabsRealtimeAuth

    /**
     * A queue of already-minted Scribe tokens. Network token acquisition must happen
     * asynchronously and call [addPreparedToken] before a socket is opened; consuming a token is
     * an immediate queue operation, so reconnect/reset can never block the microphone path.
     */
    class SingleUseToken(initialTokens: Iterable<String>) : ElevenLabsRealtimeAuth {
        constructor(initialToken: String) : this(listOf(initialToken))

        private val preparedTokens = ConcurrentLinkedQueue<String>()

        init {
            initialTokens.forEach(::addPreparedToken)
        }

        fun addPreparedToken(token: String) {
            preparedTokens.add(token.trim().also {
                require(it.isNotEmpty()) { "ElevenLabs single-use token cannot be blank" }
            })
        }

        internal fun takePreparedToken(): String = preparedTokens.poll()
            ?: throw IllegalStateException(
                "No prepared ElevenLabs single-use token; fetch one asynchronously before opening the socket"
            )
    }
}

/** ElevenLabs Scribe v2 Realtime with a manual commit on push-to-talk release. */
internal class ElevenLabsRealtimeRecognizer(
    auth: ElevenLabsRealtimeAuth,
    override val languageCode: String?,
    secondaryLanguages: List<String> = defaultSecondaryLanguages(languageCode),
    keyterms: List<String> = emptyList(),
    client: OkHttpClient = RealtimeHttpClient.instance,
    endpoint: String = ELEVENLABS_REALTIME_ENDPOINT,
    finalResultTimeoutMillis: Long = DEFAULT_FINAL_TIMEOUT_MILLIS,
    maxPreOpenAudioMillis: Int = DEFAULT_PRE_OPEN_AUDIO_MILLIS,
) : RealtimeWebSocketRecognizer(
    client = client,
    protocol = ElevenLabsRealtimeProtocol(
        auth = auth,
        languageCode = languageCode,
        secondaryLanguages = secondaryLanguages,
        keyterms = keyterms,
        endpoint = endpoint,
    ),
    finalResultTimeoutMillis = finalResultTimeoutMillis,
    maxPreOpenAudioMillis = maxPreOpenAudioMillis,
) {
    companion object {
        const val ELEVENLABS_REALTIME_ENDPOINT = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"
        const val DEFAULT_FINAL_TIMEOUT_MILLIS = 4_000L
        const val DEFAULT_PRE_OPEN_AUDIO_MILLIS = 3_000

        private fun defaultSecondaryLanguages(languageCode: String?): List<String> = when (languageCode) {
            "hi", "hin" -> listOf("en")
            "en", "eng" -> listOf("hi")
            else -> listOf("hi", "en")
        }
    }
}

class ElevenLabsRealtimeRecognizerSource(
    private val auth: ElevenLabsRealtimeAuth,
    private val displayLocale: SpeakKeysLocale,
    private val languageCode: String? = null,
    private val secondaryLanguages: List<String> = defaultSecondaryLanguages(languageCode),
    private val keyterms: List<String> = emptyList(),
    private val client: OkHttpClient = RealtimeHttpClient.instance,
    private val endpoint: String = ElevenLabsRealtimeRecognizer.ELEVENLABS_REALTIME_ENDPOINT,
    private val finalResultTimeoutMillis: Long = ElevenLabsRealtimeRecognizer.DEFAULT_FINAL_TIMEOUT_MILLIS,
    private val maxPreOpenAudioMillis: Int = ElevenLabsRealtimeRecognizer.DEFAULT_PRE_OPEN_AUDIO_MILLIS,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) : RecognizerSource {
    constructor(
        apiKey: String,
        displayLocale: SpeakKeysLocale,
        languageCode: String? = null,
    ) : this(
        auth = ElevenLabsRealtimeAuth.ApiKey(apiKey),
        displayLocale = displayLocale,
        languageCode = languageCode,
    )

    private val mutableState = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = mutableState.asStateFlow()

    private var delegate: ElevenLabsRealtimeRecognizer? = null
    @Volatile private var initializationError: String? = null

    override val recognizer: Recognizer
        get() = checkNotNull(delegate) { "ElevenLabs realtime source has not been initialized" }

    override val addSpaces: Boolean = true
    override val isBatchRecognizer: Boolean = false
    override val closed: Boolean get() = delegate == null
    override val errorMessage: String
        get() = initializationError ?: "Add an ElevenLabs API key to use Mixed-Language Streaming"
    override val name: String get() = "Mixed-Language Streaming"
    override val locale: SpeakKeysLocale get() = displayLocale

    override suspend fun initialize() {
        mutableState.value = RecognizerState.LOADING
        if (auth is ElevenLabsRealtimeAuth.ApiKey && auth.value.isBlank()) {
            delegate = null
            initializationError = "Add an ElevenLabs API key to use Mixed-Language Streaming"
            mutableState.value = RecognizerState.ERROR
            return
        }

        delegate?.cancelCurrentUtterance()
        delegate = null
        val candidate = try {
            ElevenLabsRealtimeRecognizer(
                auth = auth,
                languageCode = languageCode,
                secondaryLanguages = secondaryLanguages,
                keyterms = keyterms,
                client = client,
                endpoint = endpoint,
                finalResultTimeoutMillis = finalResultTimeoutMillis,
                maxPreOpenAudioMillis = maxPreOpenAudioMillis,
            )
        } catch (error: Exception) {
            initializationError = error.message ?: "ElevenLabs realtime configuration is invalid"
            mutableState.value = RecognizerState.ERROR
            return
        }
        delegate = candidate
        initializationError = null
        try {
            candidate.prepareAndAwaitReady(handshakeTimeoutMillis)
            if (delegate === candidate) mutableState.value = RecognizerState.READY
        } catch (error: Exception) {
            candidate.cancelCurrentUtterance()
            if (delegate === candidate) {
                delegate = null
                initializationError = error.message ?: "ElevenLabs realtime connection failed"
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

    private companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5_000L

        fun defaultSecondaryLanguages(languageCode: String?): List<String> = when (languageCode) {
            "hi", "hin" -> listOf("en")
            "en", "eng" -> listOf("hi")
            else -> listOf("hi", "en")
        }
    }
}

internal class ElevenLabsRealtimeProtocol(
    private val auth: ElevenLabsRealtimeAuth,
    private val languageCode: String?,
    private val secondaryLanguages: List<String>,
    private val keyterms: List<String>,
    private val endpoint: String,
) : RealtimeSpeechProtocol {
    init {
        if (auth is ElevenLabsRealtimeAuth.ApiKey) {
            require(auth.value.isNotBlank()) { "ElevenLabs credential cannot be blank" }
        }
        require(keyterms.size <= 50) { "ElevenLabs realtime accepts at most 50 keyterms" }
    }

    override val providerName: String = "ElevenLabs"
    override val holdLastAudioChunkForCommit: Boolean = true

    override fun request(): Request {
        val connectionCredential = when (auth) {
            is ElevenLabsRealtimeAuth.ApiKey -> ConnectionCredential.ApiKey(auth.value)
            is ElevenLabsRealtimeAuth.SingleUseToken -> ConnectionCredential.SingleUseToken(
                auth.takePreparedToken(),
            )
        }
        val url = websocketUrl(endpoint).newBuilder()
            .addQueryParameter("model_id", "scribe_v2_realtime")
            .addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("commit_strategy", "manual")
            .apply {
                languageCode?.takeIf { it.isNotBlank() }?.let { addQueryParameter("language_code", it) }
                secondaryLanguages.distinct().filter { it.isNotBlank() }.forEach {
                    addQueryParameter("secondary_languages", it)
                }
                keyterms.distinct().filter { it.isNotBlank() }.forEach { addQueryParameter("keyterms", it) }
                if (connectionCredential is ConnectionCredential.SingleUseToken) {
                    addQueryParameter("token", connectionCredential.value)
                }
            }
            .build()

        return Request.Builder()
            .url(url)
            .apply {
                if (connectionCredential is ConnectionCredential.ApiKey) {
                    header("xi-api-key", connectionCredential.value)
                }
            }
            .build()
    }

    override fun audioMessage(pcmLittleEndian: ByteArray, commit: Boolean): String = buildString {
        append("{\"message_type\":\"input_audio_chunk\",\"audio_base_64\":")
        append(RealtimeJson.quote(RealtimeJson.audioBase64(pcmLittleEndian)))
        append(",\"commit\":")
        append(commit)
        append(",\"sample_rate\":16000")
        append('}')
    }

    override fun parseServerMessage(message: String): RealtimeServerEvent {
        val json = RealtimeJson.objectOrNull(message)
            ?: return RealtimeServerEvent.Error("Malformed server message", fatal = false)
        val type = json.string("message_type", "type", "event")?.lowercase().orEmpty()
        val data = json.nestedObject("data", "result")
        val text = json.transcriptText().orEmpty()

        return when (type) {
            "session_started", "session.begin", "session_started_message" -> RealtimeServerEvent.Ready
            "partial_transcript" -> RealtimeServerEvent.Partial(text)

            // final_transcript can still be superseded until the explicit manual commit arrives.
            "final_transcript", "final_transcript_with_timestamps" -> RealtimeServerEvent.Partial(text)

            "committed_transcript", "committed_transcript_with_timestamps" -> RealtimeServerEvent.Final(text)
            else -> when {
                type.contains("error") || type in KNOWN_ERROR_TYPES -> RealtimeServerEvent.Error(
                    json.string("error", "message", "detail")
                        ?: data?.string("error", "message", "detail")
                        ?: "Unknown ElevenLabs realtime error",
                )
                else -> RealtimeServerEvent.Ignored
            }
        }
    }

    private companion object {
        val KNOWN_ERROR_TYPES = setOf(
            "auth_error",
            "quota_exceeded",
            "throttled",
            "commit_throttled",
            "unaccepted_terms",
            "rate_limited",
            "queue_overflow",
            "resource_exhausted",
            "session_time_limit_exceeded",
            "input_error",
            "chunk_size_exceeded",
            "insufficient_audio_activity",
            "transcriber_error",
        )
    }

    private sealed interface ConnectionCredential {
        val value: String

        data class ApiKey(override val value: String) : ConnectionCredential
        data class SingleUseToken(override val value: String) : ConnectionCredential
    }
}
