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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WhisperCloudRecognizer(
    private val apiKey: String,
    override val languageCode: String?,
    private val prompt: String = "",
    private val transliterateToRoman: Boolean = false
) : Recognizer {

    companion object {
        private const val TAG = "WhisperCloudRecognizer"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/audio/transcriptions"
    }

    override val sampleRate: Float = 16000f

    private val maxBufferSamples = (30 * sampleRate).toInt()
    private val audioBuffer = ShortArray(maxBufferSamples)
    private var bufferPosition = 0

    private var lastResult = ""

    private val client = HttpClient()

    override fun reset() {
        bufferPosition = 0
        lastResult = ""
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

        Logger.d(TAG, "Transcribing $bufferPosition samples (${bufferPosition / sampleRate} seconds)")
        runBlocking { transcribe() }
        val result = lastResult
        lastResult = ""
        return result
    }

    private suspend fun transcribe() {
        if (apiKey.isEmpty()) {
            Logger.e(TAG, "No API key configured")
            return
        }

        val wavBytes = WavEncoder.createWavBytes(audioBuffer, bufferPosition, sampleRate.toInt())
        Logger.d(TAG, "Created WAV: ${wavBytes.size} bytes")

        try {
            val response = client.post(OPENAI_API_URL) {
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

                lastResult = removeSpaceForLocale(text)
            } else {
                Logger.e(TAG, "API error: ${response.status.value}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Transcription failed", e)
        }

        bufferPosition = 0
    }
}
