package helium314.keyboard.voice.utils

import android.util.Log
import com.elishaazaria.sayboard.data.InstalledModelReference
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Persists the ordered list of voice models.
 *
 * Deserialization is lenient on purpose: JetPref loads preferences on a coroutine with no exception
 * handling, so a single entry it cannot decode (for example a [com.elishaazaria.sayboard.data.ModelType]
 * that only exists in a newer or different build of the app) would otherwise crash the keyboard at
 * startup. Such entries are dropped and the rest of the list is kept; the model manager then
 * re-saves the cleaned list on its next reload.
 */
class ModelListSerializer : PreferenceSerializer<List<InstalledModelReference>> {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(InstalledModelReference.serializer())

    override fun deserialize(value: String): List<InstalledModelReference> {
        // SerializationException extends IllegalArgumentException, so one catch covers both.
        val elements = try {
            json.parseToJsonElement(value) as? JsonArray ?: return emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping unreadable model list", e)
            return emptyList()
        }
        return elements.mapNotNull { element ->
            try {
                json.decodeFromJsonElement(InstalledModelReference.serializer(), element)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Dropping model entry this build cannot read: $element", e)
                null
            }
        }
    }

    override fun serialize(value: List<InstalledModelReference>): String {
        return json.encodeToString(listSerializer, value)
    }

    private companion object {
        const val TAG = "ModelListSerializer"
    }
}
