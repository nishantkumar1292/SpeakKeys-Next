package helium314.keyboard.voice.utils

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ModelListSerializerTest {
    private val serializer = ModelListSerializer()
    private val proxiedSarvam = InstalledModelReference(
        path = "proxied://sarvam",
        name = "Sarvam Cloud (Proxied)",
        type = ModelType.ProxiedSarvamCloud
    )

    @Test
    fun roundTripsKnownModels() {
        val models = listOf(
            proxiedSarvam,
            InstalledModelReference("whisper://cloud", "Whisper", ModelType.WhisperCloud)
        )
        assertEquals(models, serializer.deserialize(serializer.serialize(models)))
    }

    @Test
    fun dropsEntriesWithModelTypesThisBuildDoesNotKnow() {
        // Exactly what a build from a branch with offline models left in the datastore.
        val stored = """[{"path":"vosk://hi-small-0.22","name":"Instant Hindi","type":"VoskLocal"},""" +
            """{"path":"proxied://sarvam","name":"Sarvam Cloud (Proxied)","type":"ProxiedSarvamCloud"}]"""
        assertEquals(listOf(proxiedSarvam), serializer.deserialize(stored))
    }

    @Test
    fun ignoresExtraFieldsFromNewerBuilds() {
        val stored = """[{"path":"proxied://sarvam","name":"Sarvam Cloud (Proxied)","type":"ProxiedSarvamCloud","engine":"x"}]"""
        assertEquals(listOf(proxiedSarvam), serializer.deserialize(stored))
    }

    @Test
    fun dropsElementsThatAreNotModelObjects() {
        val stored = """[null,1,"x",{"path":"proxied://sarvam","name":"Sarvam Cloud (Proxied)","type":"ProxiedSarvamCloud"},{"path":"x"}]"""
        assertEquals(listOf(proxiedSarvam), serializer.deserialize(stored))
    }

    @Test
    fun returnsEmptyListForUnreadableInput() {
        assertEquals(emptyList(), serializer.deserialize("not json"))
        assertEquals(emptyList(), serializer.deserialize("""{"path":"x"}"""))
        assertEquals(emptyList(), serializer.deserialize(""))
    }
}
