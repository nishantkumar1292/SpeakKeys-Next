package helium314.keyboard.voice

import com.elishaazaria.sayboard.data.InstalledModelReference
import dev.patrickgold.jetpref.datastore.JetPref
import dev.patrickgold.jetpref.datastore.model.PreferenceModel
import helium314.keyboard.voice.utils.ModelListSerializer
import helium314.keyboard.voice.utils.RollbackSafeModelListSerializer
import helium314.keyboard.voice.utils.legacyCompatibleModelOrder

fun speakKeysPreferenceModel() = JetPref.getOrCreatePreferenceModel(VoicePrefs::class, ::VoicePrefs)

class VoicePrefs : PreferenceModel("speakkeys-voice-preferences") {
    /**
     * Rollback view consumed by v0.1.1. Never write the full current catalog to this preference:
     * its released serializer cannot decode local or ElevenLabs model types.
     */
    val legacyModelsOrder = custom(
        key = "sl_models_order",
        default = listOf(),
        serializer = RollbackSafeModelListSerializer()
    )

    /** Full-fidelity catalog used by current builds. */
    val modelsOrder = custom(
        key = "sl_models_order_v2",
        default = listOf(),
        serializer = ModelListSerializer()
    )

    val modelsOrderV2Initialized = boolean(
        key = "b_models_order_v2_initialized",
        default = false
    )

    val logicListenImmediately = boolean(
        key = "b_listen_immediately",
        default = false
    )

    val logicAutoCapitalize = boolean(
        key = "b_auto_capitalize",
        default = true
    )

    val keyboardHeightPortrait = float(
        key = "f_keyboard_height_portrait",
        default = 0.3f
    )

    val keyboardHeightLandscape = float(
        key = "f_keyboard_height_landscape",
        default = 0.5f
    )

    val lastSelectedModelPath = string(
        key = "s_last_selected_model_path",
        default = ""
    )

    // Whisper Cloud settings
    val whisperLanguage = string(
        key = "s_whisper_language",
        default = ""
    )

    val whisperPrompt = string(
        key = "s_whisper_prompt",
        default = "Yeh ek Hindi sentence hai jo Roman script mein likha gaya hai. Main aapko batana chahta hoon ki aaj mausam bahut achha hai."
    )

    val whisperTransliterateToRoman = boolean(
        key = "b_whisper_transliterate_to_roman",
        default = false
    )

    // Legacy plaintext API-key slots. VoiceCredentialVault imports these once into its encrypted
    // store and clears them. Keep the keys only so existing installations can migrate safely.
    val openaiApiKey = string(
        key = "s_openai_api_key",
        default = ""
    )

    val sarvamApiKey = string(
        key = "s_sarvam_api_key",
        default = ""
    )

    val elevenLabsApiKey = string(
        key = "s_elevenlabs_api_key",
        default = ""
    )

    // Sarvam settings
    val sarvamMode = string(
        key = "s_sarvam_mode",
        default = "translit"
    )

    val sarvamLanguage = string(
        key = "s_sarvam_language",
        default = "unknown"
    )

    /**
     * Plain-language output choice shared by every recognizer. `mixed` keeps
     * Hindi in Devanagari and English in Latin; `latin` asks supported engines
     * for Roman-script output.
     */
    val voiceOutputStyle = string(
        key = "s_voice_output_style",
        default = "mixed"
    )

    val selectedEngine = string(
        key = "s_selected_engine",
        default = "proxied"
    )
}

/**
 * Imports the released preference once, then keeps it parseable by v0.1.1 on every startup.
 *
 * The import reads with the current enum before rewriting the legacy key, so it also recovers
 * local model ordering from development builds that wrote the expanded schema to that key.
 */
fun VoicePrefs.migrateModelOrderPreferences() {
    if (modelsOrderV2Initialized.get()) {
        legacyModelsOrder.set(legacyCompatibleModelOrder(modelsOrder.get()))
        return
    }

    val legacyOrder = legacyModelsOrder.get()
    val currentOrder = modelsOrder.get().takeIf { it.isNotEmpty() } ?: legacyOrder
    // Make the released key safe before committing the expanded catalog. This also makes a retry
    // recoverable if the process is interrupted between the two preference writes.
    legacyModelsOrder.set(legacyCompatibleModelOrder(currentOrder))
    modelsOrder.set(currentOrder)
    modelsOrderV2Initialized.set(true)
}

/** Writes the current catalog and its rollback-safe v0.1.1 projection together. */
fun VoicePrefs.setModelsOrder(models: List<InstalledModelReference>) {
    legacyModelsOrder.set(legacyCompatibleModelOrder(models))
    modelsOrder.set(models)
}
