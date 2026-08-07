package com.elishaazaria.sayboard.recognition.recognizers.sources

import com.elishaazaria.sayboard.recognition.audio.WavEncoder
import com.elishaazaria.sayboard.recognition.logging.Logger
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.utils.DevanagariTransliterator
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Recognizer that sends audio to our Firebase Cloud Functions proxy
 * instead of directly to OpenAI/Sarvam. The proxy handles API keys server-side.
 */
class ProxiedCloudRecognizer(
    private val tokenProvider: suspend () -> String?,
    private val provider: String, // "whisper" or "sarvam"
    override val languageCode: String?,
    private val providerParams: Map<String, String> = emptyMap(),
    private val transliterateToRoman: Boolean = false,
    private val proxyBaseUrl: String = PROXY_BASE_URL,
    private val client: HttpClient = HttpClient(),
    private val maxRetries: Int = MAX_RETRIES,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : Recognizer {

    init {
        require(maxRetries >= 0) { "maxRetries cannot be negative" }
        require(retryDelayMs >= 0) { "retryDelayMs cannot be negative" }
    }

    companion object {
        private const val TAG = "ProxiedCloudRecognizer"
        const val PROXY_BASE_URL = "https://asia-south1-speakkeys.cloudfunctions.net"
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 250L
    }

    override val sampleRate: Float = 16000f

    private val maxBufferSamples = (30 * sampleRate).toInt()
    private val audioBuffer = ShortArray(maxBufferSamples)
    private var bufferPosition = 0
    private var acceptingAudio = false
    private val bufferMutex = Mutex()
    private val requestGate = CancellableRecognitionRequest()

    override fun reset() {
        requestGate.beginGeneration()
        clearAudioBuffer(acceptNewAudio = true)
    }

    override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
        if (buffer == null || nread <= 0) return false
        if (!bufferMutex.tryLock()) return false
        try {
            if (!acceptingAudio) return false
            val samplesToAdd = minOf(nread, buffer.size, maxBufferSamples - bufferPosition)
            if (samplesToAdd > 0) {
                buffer.copyInto(audioBuffer, bufferPosition, 0, samplesToAdd)
                bufferPosition += samplesToAdd
            }
            return bufferPosition >= maxBufferSamples
        } finally {
            bufferMutex.unlock()
        }
    }

    override fun getResult(): String = ""

    override fun getPartialResult(): String = ""

    override fun getFinalResult(): String {
        val generation = requestGate.currentGeneration()
        val samples = runBlocking {
            bufferMutex.withLock {
                if (!acceptingAudio || bufferPosition == 0) null
                else audioBuffer.copyOf(bufferPosition)
            }
        } ?: return ""

        Logger.d(TAG, "Transcribing ${samples.size} samples via proxy ($provider)")
        try {
            return runBlocking {
                requestGate.runIfCurrent(generation) { transcribe(samples) }
            }.orEmpty()
        } catch (_: CancellationException) {
            return ""
        } finally {
            requestGate.ifCurrent(generation) { clearAudioBuffer(acceptNewAudio = false) }
        }
    }

    override fun cancel() {
        requestGate.cancelGeneration()
        clearAudioBuffer(acceptNewAudio = false)
    }

    fun close() {
        cancel()
        client.close()
    }

    private suspend fun transcribe(samples: ShortArray): String {
        val wavBytes = WavEncoder.createWavBytes(samples, samples.size, sampleRate.toInt())
        Logger.d(TAG, "Created WAV: ${wavBytes.size} bytes")
        var lastError: Exception? = null

        for (attempt in 0..maxRetries) {
            var shouldRetry = false
            try {
                val freshToken = tokenProvider()
                if (freshToken.isNullOrEmpty()) {
                    lastError = Exception("No valid auth token available")
                    Logger.e(TAG, "Token provider returned null/empty")
                } else {
                    val response = client.post("${proxyBaseUrl.trimEnd('/')}/transcribe") {
                        header("Authorization", "Bearer $freshToken")
                        setBody(MultiPartFormDataContent(formData {
                            append("file", wavBytes, Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                            })
                            append("provider", provider)
                            for ((key, value) in providerParams) {
                                if (value.isNotEmpty()) {
                                    append(key, value)
                                }
                            }
                        }))
                    }

                    val responseBody = response.bodyAsText()
                    Logger.d(TAG, "Proxy response: ${response.status.value} (attempt ${attempt + 1})")

                    when {
                        response.status.value in 200..299 -> {
                            val json = Json.parseToJsonElement(responseBody).jsonObject
                            var text = when (provider) {
                                "sarvam" -> json["transcript"]?.jsonPrimitive?.content?.trim() ?: ""
                                else -> json["text"]?.jsonPrimitive?.content?.trim() ?: ""
                            }

                            if (transliterateToRoman) {
                                text = DevanagariTransliterator.transliterate(text)
                            }

                            return removeSpaceForLocale(text)
                        }
                        else -> {
                            val message = extractErrorMessage(responseBody)
                            val status = response.status.value
                            shouldRetry = status.isTransientHttpFailure()
                            val logMessage = "Proxy error: $status - $message " +
                                "(attempt ${attempt + 1}/${maxRetries + 1}, retry=$shouldRetry)"
                            if (status == 401 || status == 403 || status == 402) {
                                Logger.w(TAG, logMessage)
                            } else {
                                Logger.e(TAG, logMessage)
                            }
                            lastError = Exception("Proxy error ${response.status.value}: $message")
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Logger.e(
                    TAG,
                    "Transcription via proxy failed (attempt ${attempt + 1}/${maxRetries + 1})",
                    e,
                )
                lastError = e
                // Transport failures are transient. Parsing, validation, and other deterministic
                // failures happen after a successful response and are returned immediately.
                shouldRetry = e.isLikelyTransportFailure()
            }

            if (!shouldRetry) break
            if (attempt < maxRetries) delay(retryDelayMs)
        }

        Logger.e(TAG, "All ${maxRetries + 1} transcription attempts failed", lastError)
        throw lastError ?: Exception("Proxy transcription failed")
    }

    private fun Int.isTransientHttpFailure(): Boolean =
        this == 408 || this == 425 || this == 429 || this in 500..599

    private fun Exception.isLikelyTransportFailure(): Boolean = when (this) {
        is io.ktor.client.plugins.HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.utils.io.errors.IOException -> true
        else -> false
    }

    private fun extractErrorMessage(responseBody: String): String {
        if (responseBody.isBlank()) return "Empty response body"
        return try {
            val json = Json.parseToJsonElement(responseBody).jsonObject
            json["error"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: json["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: responseBody.take(200)
        } catch (_: Exception) {
            responseBody.take(200)
        }
    }

    private fun clearAudioBuffer(acceptNewAudio: Boolean) {
        runBlocking {
            bufferMutex.withLock {
                audioBuffer.fill(0, 0, bufferPosition)
                bufferPosition = 0
                acceptingAudio = acceptNewAudio
            }
        }
    }
}
