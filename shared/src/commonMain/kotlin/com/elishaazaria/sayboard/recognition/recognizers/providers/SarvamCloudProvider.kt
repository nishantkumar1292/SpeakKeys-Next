package com.elishaazaria.sayboard.recognition.recognizers.providers

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.sources.SarvamCloud

class SarvamCloudProvider(private val prefs: PreferencesRepository) : RecognizerSourceProvider {

    override fun getInstalledModels(): List<InstalledModelReference> {
        val apiKey = prefs.getSarvamApiKey()
        if (apiKey.isEmpty()) {
            return emptyList()
        }

        return listOf(
            InstalledModelReference(
                path = "sarvam://cloud",
                name = "Sarvam Cloud (Hinglish)",
                type = ModelType.SarvamCloud
            )
        )
    }

    override fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        if (localModel.type != ModelType.SarvamCloud) return null

        val apiKey = prefs.getSarvamApiKey()
        if (apiKey.isEmpty()) return null

        val locale = SpeakKeysLocale("en", "IN")
        val mode = normalizeSarvamMode(prefs.getSarvamMode())
        val languageCode = prefs.getSarvamLanguage()

        return SarvamCloud(apiKey, locale, mode, languageCode)
    }

    private fun normalizeSarvamMode(mode: String): String = when (mode) {
        "native" -> "transcribe"
        "transcribe", "translit" -> mode
        else -> "translit"
    }
}
