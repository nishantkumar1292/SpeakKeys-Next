package helium314.keyboard.voice.streaming

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.ByteString.Companion.toByteString

internal interface RealtimeSpeechProtocol {
    val providerName: String
    val holdLastAudioChunkForCommit: Boolean
    val waitForReadyEventBeforeAudio: Boolean get() = true

    fun request(): Request
    fun sessionStartMessages(): List<String> = emptyList()
    fun audioMessage(pcmLittleEndian: ByteArray, commit: Boolean): String
    /**
     * Returns a binary WebSocket payload when a backend accepts raw PCM frames directly.
     * Existing third-party protocols return null and retain their JSON/base64 envelopes.
     */
    fun binaryAudioMessage(pcmLittleEndian: ByteArray, commit: Boolean): ByteArray? = null
    fun finishMessages(): List<String> = emptyList()
    fun parseServerMessage(message: String): RealtimeServerEvent
    fun onHandshakeHttpFailure(statusCode: Int) = Unit
}

internal class RealtimeAuthenticationException(message: String) :
    IllegalStateException(message)

internal sealed interface RealtimeServerEvent {
    data object Ready : RealtimeServerEvent
    data class Partial(val text: String) : RealtimeServerEvent
    data class Final(val text: String) : RealtimeServerEvent
    data class Error(val message: String, val fatal: Boolean = true) : RealtimeServerEvent
    data object Ignored : RealtimeServerEvent
}

internal object RealtimeJson {
    private val parser = Json { ignoreUnknownKeys = true }

    fun objectOrNull(message: String): JsonObject? = runCatching {
        parser.parseToJsonElement(message).jsonObject
    }.getOrNull()

    fun JsonObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    fun JsonObject.nestedObject(vararg keys: String): JsonObject? {
        for (key in keys) {
            val value = this[key] as? JsonObject
            if (value != null) return value
        }
        return null
    }

    fun JsonObject.transcriptText(): String? {
        string("text", "transcript")?.let { return it }
        return nestedObject("data", "result", "transcript")
            ?.string("text", "transcript", "content")
    }

    fun JsonObject.boolean(vararg keys: String): Boolean? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.booleanOrNull
            if (value != null) return value
        }
        return null
    }

    fun JsonObject.int(vararg keys: String): Int? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.intOrNull
            if (value != null) return value
        }
        return null
    }

    fun audioBase64(bytes: ByteArray): String = bytes.toByteString().base64()

    fun quote(value: String): String = JsonPrimitive(value).toString()

    fun primitive(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
}
