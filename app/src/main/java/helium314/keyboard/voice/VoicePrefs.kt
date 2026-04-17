package helium314.keyboard.voice

import helium314.keyboard.voice.utils.ModelListSerializer
import dev.patrickgold.jetpref.datastore.JetPref
import dev.patrickgold.jetpref.datastore.model.PreferenceModel

fun speakKeysPreferenceModel() = JetPref.getOrCreatePreferenceModel(VoicePrefs::class, ::VoicePrefs)

class VoicePrefs : PreferenceModel("speakkeys-voice-preferences") {
    val modelsOrder = custom(
        key = "sl_models_order",
        default = listOf(),
        serializer = ModelListSerializer()
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

    // API Keys
    val openaiApiKey = string(
        key = "s_openai_api_key",
        default = ""
    )

    val sarvamApiKey = string(
        key = "s_sarvam_api_key",
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

    val selectedEngine = string(
        key = "s_selected_engine",
        default = "proxied"
    )
}
