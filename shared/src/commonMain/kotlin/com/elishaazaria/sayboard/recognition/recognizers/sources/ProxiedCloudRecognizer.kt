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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
    private val transliterateToRoman: Boolean = false
) : Recognizer {

    companion object {
        private const val TAG = "ProxiedCloudRecognizer"
        const val PROXY_BASE_URL = "https://asia-south1-speakkeys.cloudfunctions.net"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1500L
    }

    override val sampleRate: Float = 16000f

    private val maxBufferSamples = (30 * sampleRate).toInt()
    private val audioBuffer = ShortArray(maxBufferSamples)
    private var bufferPosition = 0

    private var lastResult = ""
    private var lastError: Exception? = null

    private val client = HttpClient()

    override fun reset() {
        bufferPosition = 0
        lastResult = ""
        lastError = null
    }

    override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
        if (buffer == null || nread <= 0) return false

        val samplesToAdd = minOf(nread, maxBufferSamples - bufferPosition)
        if (samplesToAdd > 0) {
            buffer.copyInto(audioBuffer, bufferPosition, 0, samplesToAdd)
            bufferPosition += samplesToAdd
        }

        return bufferPosition >= maxBufferSamples
    }

    override fun getResult(): String = ""

    override fun getPartialResult(): String = ""

    override fun getFinalResult(): String {
        if (bufferPosition == 0) return ""

        Logger.d(TAG, "Transcribing $bufferPosition samples via proxy ($provider)")
        try {
            runBlocking { transcribe() }
            lastError?.let { throw it }
            return lastResult
        } finally {
            bufferPosition = 0
            lastResult = ""
            lastError = null
        }
    }

    private suspend fun transcribe() {
        val wavBytes = WavEncoder.createWavBytes(audioBuffer, bufferPosition, sampleRate.toInt())
        Logger.d(TAG, "Created WAV: ${wavBytes.size} bytes")
        lastError = null

        for (attempt in 0..MAX_RETRIES) {
            try {
                val freshToken = tokenProvider()
                if (freshToken.isNullOrEmpty()) {
                    lastError = Exception("No valid auth token available")
                    Logger.e(TAG, "Token provider returned null/empty (attempt ${attempt + 1})")
                    if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
                    continue
                }

                val response = client.post("$PROXY_BASE_URL/transcribe") {
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

                        lastError = null
                        lastResult = removeSpaceForLocale(text)
                        return
                    }
                    response.status.value == 402 -> {
                        val message = extractErrorMessage(responseBody)
                        Logger.w(TAG, "Access denied: ${response.status.value} - $message")
                        lastError = Exception("Proxy access denied: $message")
                        return
                    }
                    else -> {
                        val message = extractErrorMessage(responseBody)
                        Logger.e(TAG, "Proxy error: ${response.status.value} - $message (attempt ${attempt + 1}/${MAX_RETRIES + 1})")
                        lastError = Exception("Proxy error ${response.status.value}: $message")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Transcription via proxy failed (attempt ${attempt + 1}/${MAX_RETRIES + 1})", e)
                lastError = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        Logger.e(TAG, "All ${MAX_RETRIES + 1} transcription attempts failed", lastError)
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
}
