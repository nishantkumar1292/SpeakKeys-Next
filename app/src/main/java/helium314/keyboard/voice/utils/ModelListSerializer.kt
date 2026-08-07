package helium314.keyboard.voice.utils

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ModelListSerializer : PreferenceSerializer<List<InstalledModelReference>> {
    private val serializer = ListSerializer(InstalledModelReference.serializer())
    override fun deserialize(value: String): List<InstalledModelReference> {
        return Json.decodeFromString(serializer, value)
    }

    override fun serialize(value: List<InstalledModelReference>): String {
        return Json.encodeToString(serializer, value)
    }
}

/**
 * Serializer for the model-order key read by SpeakKeys v0.1.1.
 *
 * That release used a strict enum containing only the four cloud model types below. Deserialization
 * deliberately accepts the current model schema so builds that briefly wrote a local model to the
 * legacy key can still migrate it. Serialization always projects back to the v0.1.1 schema.
 */
class RollbackSafeModelListSerializer : PreferenceSerializer<List<InstalledModelReference>> {
    private val delegate = ModelListSerializer()

    override fun deserialize(value: String): List<InstalledModelReference> =
        delegate.deserialize(value)

    override fun serialize(value: List<InstalledModelReference>): String =
        delegate.serialize(legacyCompatibleModelOrder(value))
}

internal fun legacyCompatibleModelOrder(
    models: List<InstalledModelReference>,
): List<InstalledModelReference> = models.filter { model ->
    model.type in ROLLBACK_SAFE_MODEL_TYPES
}

private val ROLLBACK_SAFE_MODEL_TYPES = setOf(
    ModelType.WhisperCloud,
    ModelType.SarvamCloud,
    ModelType.ProxiedWhisperCloud,
    ModelType.ProxiedSarvamCloud,
)
