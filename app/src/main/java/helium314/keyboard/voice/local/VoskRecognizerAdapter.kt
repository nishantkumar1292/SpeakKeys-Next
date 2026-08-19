// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Recognizer as NativeVoskRecognizer

internal interface VoskRecognizerBackend : AutoCloseable {
    fun reset()
    fun acceptWaveForm(buffer: ShortArray, length: Int): Boolean
    fun result(): String
    fun partialResult(): String
    fun finalResult(): String
}

private class NativeVoskRecognizerBackend(
    private val recognizer: NativeVoskRecognizer,
) : VoskRecognizerBackend {
    override fun reset() = recognizer.reset()
    override fun acceptWaveForm(buffer: ShortArray, length: Int): Boolean =
        recognizer.acceptWaveForm(buffer, length)

    override fun result(): String = recognizer.result
    override fun partialResult(): String = recognizer.partialResult
    override fun finalResult(): String = recognizer.finalResult
    override fun close() = recognizer.close()
}

/** Adapts Vosk's JSON API to SpeakKeys' plain-text, app-owned PCM recognizer contract. */
class VoskRecognizerAdapter internal constructor(
    private val backend: VoskRecognizerBackend,
    override val languageCode: String,
) : Recognizer, AutoCloseable {
    internal constructor(recognizer: NativeVoskRecognizer, languageCode: String) : this(
        NativeVoskRecognizerBackend(recognizer),
        languageCode,
    )

    @Volatile
    private var isClosed = false

    override val sampleRate: Float = SAMPLE_RATE_HZ

    override fun reset() {
        checkOpen()
        backend.reset()
    }

    override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
        checkOpen()
        if (buffer == null || nread <= 0) return false
        require(nread <= buffer.size) { "PCM length exceeds the supplied buffer" }
        return backend.acceptWaveForm(buffer, nread)
    }

    override fun getResult(): String {
        checkOpen()
        return VoskResultJson.text(backend.result(), "text")
    }

    override fun getPartialResult(): String {
        checkOpen()
        return VoskResultJson.text(backend.partialResult(), "partial")
    }

    override fun getFinalResult(): String {
        checkOpen()
        return VoskResultJson.text(backend.finalResult(), "text")
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            backend.close()
        }
    }

    private fun checkOpen() = check(!isClosed) { "Vosk recognizer is closed" }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000f
    }
}

internal object VoskResultJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun text(payload: String, key: String): String {
        if (payload.isBlank()) return ""
        return try {
            json.parseToJsonElement(payload)
                .jsonObject[key]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
        } catch (failure: Exception) {
            throw IllegalStateException("Vosk returned malformed recognition JSON", failure)
        }
    }
}
