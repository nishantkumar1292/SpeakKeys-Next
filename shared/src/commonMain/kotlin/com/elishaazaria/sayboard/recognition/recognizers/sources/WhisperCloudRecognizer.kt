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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WhisperCloudRecognizer(
    private val apiKey: String,
    override val languageCode: String?,
    private val prompt: String = "",
    private val transliterateToRoman: Boolean = false,
    private val endpoint: String = OPENAI_API_URL,
    private val client: HttpClient = HttpClient(),
) : Recognizer {

    companion object {
        private const val TAG = "WhisperCloudRecognizer"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/audio/transcriptions"
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

        Logger.d(TAG, "Transcribing ${samples.size} samples (${samples.size / sampleRate} seconds)")
        return try {
            runBlocking {
                requestGate.runIfCurrent(generation) { transcribe(samples) }
            }.orEmpty()
        } catch (_: CancellationException) {
            ""
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
        if (apiKey.isEmpty()) {
            Logger.e(TAG, "No API key configured")
            return ""
        }

        val wavBytes = WavEncoder.createWavBytes(samples, samples.size, sampleRate.toInt())
        Logger.d(TAG, "Created WAV: ${wavBytes.size} bytes")

        return try {
            val response = client.post(endpoint) {
                header("Authorization", "Bearer $apiKey")
                setBody(MultiPartFormDataContent(formData {
                    append("file", wavBytes, Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                    })
                    append("model", "whisper-1")
                    languageCode?.takeIf { it.isNotEmpty() && it != "und" }?.let { lang ->
                        append("language", lang)
                    }
                    if (prompt.isNotEmpty()) {
                        append("prompt", prompt)
                    }
                }))
            }

            val responseBody = response.bodyAsText()
            if (response.status.value in 200..299) {
                val json = Json.parseToJsonElement(responseBody).jsonObject
                var text = json["text"]?.jsonPrimitive?.content?.trim() ?: ""

                if (transliterateToRoman) {
                    text = DevanagariTransliterator.transliterate(text)
                }

                removeSpaceForLocale(text)
            } else {
                Logger.e(TAG, "API error: ${response.status.value}")
                ""
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Logger.e(TAG, "Transcription failed", e)
            ""
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
