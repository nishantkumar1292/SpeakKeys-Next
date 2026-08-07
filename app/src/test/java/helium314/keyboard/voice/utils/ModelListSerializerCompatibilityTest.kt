// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.utils

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelListSerializerCompatibilityTest {
    private val currentSerializer = ModelListSerializer()
    private val rollbackSerializer = RollbackSafeModelListSerializer()

    @Test
    fun currentV2FormatPreservesLocalAndTaraOrdering() {
        val currentOrder = currentModelOrder()

        val restored = currentSerializer.deserialize(currentSerializer.serialize(currentOrder))

        assertEquals(currentOrder, restored)
        assertEquals("android://on-device", restored.first().path)
        assertEquals("speakkeys://tara", restored[1].path)
    }

    @Test
    fun releasedV011SerializerCanParseTheMirroredLegacyFormat() {
        val encoded = rollbackSerializer.serialize(currentModelOrder())

        val restoredByFrozenRelease = Json.decodeFromString(
            ListSerializer(FrozenV011InstalledModelReference.serializer()),
            encoded,
        )

        assertEquals(
            listOf(
                FrozenV011InstalledModelReference(
                    path = "speakkeys://tara",
                    name = "Natural Hinglish",
                    type = FrozenV011ModelType.ProxiedWhisperCloud,
                ),
                FrozenV011InstalledModelReference(
                    path = "sarvam://cloud",
                    name = "Fast Hindi + English",
                    type = FrozenV011ModelType.SarvamCloud,
                ),
            ),
            restoredByFrozenRelease,
        )
        assertFalse(encoded.contains("AndroidOnDevice"))
        assertFalse(encoded.contains("VoskLocal"))
        assertFalse(encoded.contains("ElevenLabsCloud"))
    }

    @Test
    fun migrationReaderRecoversAnExpandedCatalogPreviouslyWrittenToLegacyKey() {
        val unsafeLegacyJson = currentSerializer.serialize(currentModelOrder())

        val recoveredForV2 = rollbackSerializer.deserialize(unsafeLegacyJson)

        assertEquals(currentModelOrder(), recoveredForV2)
        assertTrue(recoveredForV2.any { it.type == ModelType.AndroidOnDevice })
        assertTrue(recoveredForV2.any { it.type == ModelType.VoskLocal })
    }

    private fun currentModelOrder() = listOf(
        InstalledModelReference(
            path = "android://on-device",
            name = "Phone's built-in voice typing",
            type = ModelType.AndroidOnDevice,
        ),
        InstalledModelReference(
            path = "speakkeys://tara",
            name = "Natural Hinglish",
            type = ModelType.ProxiedWhisperCloud,
        ),
        InstalledModelReference(
            path = "vosk://hi-small-0.22",
            name = "Instant Hindi",
            type = ModelType.VoskLocal,
        ),
        InstalledModelReference(
            path = "elevenlabs://scribe-v2-realtime",
            name = "Detailed Hindi + English",
            type = ModelType.ElevenLabsCloud,
        ),
        InstalledModelReference(
            path = "sarvam://cloud",
            name = "Fast Hindi + English",
            type = ModelType.SarvamCloud,
        ),
    )
}

/** Exact serialized model schema shipped by v0.1.1. */
@Serializable
private data class FrozenV011InstalledModelReference(
    val path: String,
    val name: String,
    val type: FrozenV011ModelType,
)

@Serializable
private enum class FrozenV011ModelType {
    WhisperCloud,
    SarvamCloud,
    ProxiedWhisperCloud,
    ProxiedSarvamCloud,
}
