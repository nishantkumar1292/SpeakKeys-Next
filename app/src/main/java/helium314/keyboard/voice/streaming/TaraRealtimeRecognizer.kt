// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.recognition.recognizers.RecoverableAuthFailureSource
import com.elishaazaria.sayboard.utils.DevanagariTransliterator
import helium314.keyboard.voice.streaming.RealtimeJson.int
import helium314.keyboard.voice.streaming.RealtimeJson.string
import helium314.keyboard.voice.streaming.RealtimeJson.transcriptText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams microphone PCM to SpeakKeys while the user is talking. Tara itself is a windowed model:
 * the server performs exactly one final decode after [TaraRealtimeProtocol.finishMessages].
 */
internal class TaraRealtimeRecognizer(
    bearerToken: String,
    override val languageCode: String?,
    transliterateToRoman: Boolean = false,
    client: OkHttpClient = RealtimeHttpClient.instance,
    endpoint: String,
    bearerTokenProvider: ((forceRefresh: Boolean) -> String)? = null,
    allowInsecureEndpoint: Boolean = false,
    finalResultTimeoutMillis: Long = DEFAULT_FINAL_TIMEOUT_MILLIS,
    maxPreOpenAudioMillis: Int = DEFAULT_PRE_OPEN_AUDIO_MILLIS,
) : RealtimeWebSocketRecognizer(
    client = client,
    protocol = TaraRealtimeProtocol(
        bearerToken = bearerToken,
        bearerTokenProvider = bearerTokenProvider,
        endpoint = endpoint,
        transliterateToRoman = transliterateToRoman,
        allowInsecureEndpoint = allowInsecureEndpoint,
    ),
    finalResultTimeoutMillis = finalResultTimeoutMillis,
    maxPreOpenAudioMillis = maxPreOpenAudioMillis,
) {
    companion object {
        const val DEFAULT_FINAL_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_PRE_OPEN_AUDIO_MILLIS = 3_000
    }
}

class TaraRealtimeRecognizerSource(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val endpoint: String,
    private val displayLocale: SpeakKeysLocale = SpeakKeysLocale("hi", "IN"),
    private val transliterateToRoman: Boolean = false,
    private val client: OkHttpClient = RealtimeHttpClient.instance,
    private val finalResultTimeoutMillis: Long = TaraRealtimeRecognizer.DEFAULT_FINAL_TIMEOUT_MILLIS,
    private val maxPreOpenAudioMillis: Int = TaraRealtimeRecognizer.DEFAULT_PRE_OPEN_AUDIO_MILLIS,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val allowInsecureEndpoint: Boolean = false,
) : RecognizerSource, RecoverableAuthFailureSource {
    private val mutableState = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = mutableState.asStateFlow()

    private var delegate: TaraRealtimeRecognizer? = null
    @Volatile private var initializationError: String? = null
    override var hasRecoverableAuthFailure: Boolean = false
        private set

    override val recognizer: Recognizer
        get() = checkNotNull(delegate) { "Tara source has not been initialized" }

    override val addSpaces: Boolean = true
    // Audio transport is live, even though Tara performs its final model decode on release.
    override val isBatchRecognizer: Boolean = false
    override val closed: Boolean get() = delegate == null
    override val errorMessage: String
        get() = initializationError ?: "Sign in to use Natural Hinglish"
    override val name: String get() = "Natural Hinglish"
    override val locale: SpeakKeysLocale get() = displayLocale

    override suspend fun initialize() {
        mutableState.value = RecognizerState.LOADING
        delegate?.cancelCurrentUtterance()
        delegate = null
        initializationError = null
        hasRecoverableAuthFailure = false

        if (endpoint.isBlank()) {
            initializationError = "Natural Hinglish is not available in this build"
            mutableState.value = RecognizerState.ERROR
            return
        }

        for (attempt in 0..1) {
            val forceRefresh = attempt == 1
            val token = try {
                tokenProvider(forceRefresh)?.trim()
            } catch (error: Exception) {
                initializationError = error.message ?: "Could not start a SpeakKeys voice session"
                hasRecoverableAuthFailure = true
                mutableState.value = RecognizerState.ERROR
                return
            }
            if (token.isNullOrEmpty()) {
                initializationError = "Sign in to use Natural Hinglish"
                hasRecoverableAuthFailure = true
                mutableState.value = RecognizerState.ERROR
                return
            }

            val initialToken = AtomicReference<String?>(token)
            val candidate = try {
                TaraRealtimeRecognizer(
                    bearerToken = token,
                    bearerTokenProvider = { refreshForSocket ->
                        val seededToken = if (refreshForSocket) {
                            initialToken.set(null)
                            null
                        } else {
                            initialToken.getAndSet(null)
                        }
                        seededToken ?: runBlocking(Dispatchers.IO) {
                            tokenProvider(refreshForSocket)?.trim()
                        }.takeUnless { it.isNullOrEmpty() }
                            ?: throw TaraAuthenticationException(
                                "Sign in again to use Natural Hinglish",
                            )
                    },
                    languageCode = displayLocale.language,
                    transliterateToRoman = transliterateToRoman,
                    client = client,
                    endpoint = endpoint,
                    allowInsecureEndpoint = allowInsecureEndpoint,
                    finalResultTimeoutMillis = finalResultTimeoutMillis,
                    maxPreOpenAudioMillis = maxPreOpenAudioMillis,
                )
            } catch (error: Exception) {
                initializationError = error.message ?: "Natural Hinglish configuration is invalid"
                mutableState.value = RecognizerState.ERROR
                return
            }
            delegate = candidate
            try {
                candidate.prepareAndAwaitReady(handshakeTimeoutMillis)
                if (delegate === candidate) mutableState.value = RecognizerState.READY
                return
            } catch (error: Exception) {
                candidate.cancelCurrentUtterance()
                if (delegate === candidate) delegate = null
                val authenticationFailure =
                    error.hasCause<TaraAuthenticationException>() ||
                        error.hasCause<RealtimeAuthenticationException>()
                if (authenticationFailure && !forceRefresh) continue

                initializationError = error.message ?: "Natural Hinglish could not connect"
                hasRecoverableAuthFailure = authenticationFailure
                mutableState.value = RecognizerState.ERROR
                return
            }
        }
    }

    override fun close(freeRAM: Boolean) {
        if (freeRAM) {
            delegate?.cancelCurrentUtterance()
            delegate = null
            mutableState.value = RecognizerState.CLOSED
        } else {
            delegate?.cleanupAfterUtteranceOrIdle()
        }
    }

    private companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 5_000L
    }
}

internal class TaraRealtimeProtocol(
    private val bearerToken: String,
    private val bearerTokenProvider: ((forceRefresh: Boolean) -> String)? = null,
    private val endpoint: String,
    private val transliterateToRoman: Boolean = false,
    private val allowInsecureEndpoint: Boolean = false,
) : RealtimeSpeechProtocol {
    private val forceTokenRefresh = AtomicBoolean(false)
    init {
        require(bearerToken.isNotBlank()) { "SpeakKeys session token cannot be blank" }
        require(endpoint.isNotBlank()) { "Tara endpoint cannot be blank" }
        val parsedEndpoint = websocketUrl(endpoint)
        val isLoopback = parsedEndpoint.host == "localhost" ||
            parsedEndpoint.host == "127.0.0.1" || parsedEndpoint.host == "::1"
        val isExplicitLoopbackTestEndpoint = allowInsecureEndpoint && isLoopback &&
            (endpoint.startsWith("ws://", ignoreCase = true) ||
                endpoint.startsWith("http://", ignoreCase = true))
        require(
            endpoint.startsWith("wss://", ignoreCase = true) ||
                isExplicitLoopbackTestEndpoint
        ) {
            "Natural Hinglish requires an encrypted wss:// endpoint"
        }
    }

    override val providerName: String = "Natural Hinglish"
    override val holdLastAudioChunkForCommit: Boolean = false

    override fun request(): Request {
        val currentBearerToken = bearerTokenProvider
            ?.invoke(forceTokenRefresh.getAndSet(false))
            ?.trim()
            ?: bearerToken
        if (currentBearerToken.isBlank()) {
            throw TaraAuthenticationException("Sign in again to use Natural Hinglish")
        }
        return Request.Builder()
            .url(websocketUrl(endpoint))
            .header("Authorization", "Bearer $currentBearerToken")
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
            .build()
    }

    override fun onHandshakeHttpFailure(statusCode: Int) {
        if (statusCode == 401 || statusCode == 403) forceTokenRefresh.set(true)
    }

    override fun sessionStartMessages(): List<String> = listOf(
        "{\"type\":\"session.start\",\"protocol_version\":$PROTOCOL_VERSION," +
            "\"encoding\":\"pcm_s16le\",\"sample_rate\":16000," +
            "\"language\":\"hi\",\"mode\":\"mixed\"}",
    )

    override fun binaryAudioMessage(pcmLittleEndian: ByteArray, commit: Boolean): ByteArray =
        pcmLittleEndian

    override fun audioMessage(pcmLittleEndian: ByteArray, commit: Boolean): String =
        error("Tara uses binary PCM WebSocket frames")

    override fun finishMessages(): List<String> = listOf("{\"type\":\"input.commit\"}")

    override fun parseServerMessage(message: String): RealtimeServerEvent {
        val json = RealtimeJson.objectOrNull(message)
            ?: return RealtimeServerEvent.Error("Malformed server message", fatal = false)
        val type = json.string("type", "event")?.lowercase().orEmpty()
        val text = json.transcriptText().orEmpty().let(::formatOutput)
        return when (type) {
            "session.ready" -> if (
                json.int("protocol_version") == PROTOCOL_VERSION &&
                json.int("sample_rate") == SAMPLE_RATE_HZ
            ) {
                RealtimeServerEvent.Ready
            } else {
                RealtimeServerEvent.Error(
                    "Natural Hinglish returned an incompatible voice session",
                )
            }
            "transcript.partial" -> RealtimeServerEvent.Partial(text)
            "transcript.final" -> RealtimeServerEvent.Final(text)
            "error" -> RealtimeServerEvent.Error(
                json.string("message", "error", "detail") ?: "Unknown transcription error",
            )
            else -> RealtimeServerEvent.Ignored
        }
    }

    private fun formatOutput(text: String): String = if (transliterateToRoman) {
        DevanagariTransliterator.transliterate(text)
    } else {
        text
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val PROTOCOL_HEADER = "X-SpeakKeys-Protocol"
        private const val SAMPLE_RATE_HZ = 16_000
    }
}

internal class TaraAuthenticationException(message: String) : IllegalStateException(message)

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}
