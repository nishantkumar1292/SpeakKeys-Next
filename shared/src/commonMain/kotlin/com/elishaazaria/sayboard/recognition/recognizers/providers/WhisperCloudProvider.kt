package com.elishaazaria.sayboard.recognition.recognizers.providers

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.sources.WhisperCloud

class WhisperCloudProvider(private val prefs: PreferencesRepository) : RecognizerSourceProvider {

    override fun getInstalledModels(): List<InstalledModelReference> {
        val apiKey = prefs.getOpenaiApiKey()
        if (apiKey.isEmpty()) {
            return emptyList()
        }

        return listOf(
            InstalledModelReference(
                path = "whisper://cloud",
                name = "Whisper Cloud (OpenAI)",
                type = ModelType.WhisperCloud
            )
        )
    }

    override fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        if (localModel.type != ModelType.WhisperCloud) return null

        val apiKey = prefs.getOpenaiApiKey()
        if (apiKey.isEmpty()) return null

        val languageCode = prefs.getWhisperLanguage()
        val locale = if (languageCode.isNotEmpty()) {
            SpeakKeysLocale(languageCode)
        } else {
            SpeakKeysLocale.ROOT
        }

        val prompt = prefs.getWhisperPrompt()
        val transliterateToRoman = prefs.getWhisperTransliterateToRoman()

        return WhisperCloud(apiKey, locale, prompt, transliterateToRoman)
    }
}
