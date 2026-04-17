package com.elishaazaria.sayboard.recognition.preferences

import com.elishaazaria.sayboard.data.InstalledModelReference

/**
 * Platform-agnostic interface for accessing preferences needed by the recognition pipeline.
 * On Android, this wraps AppPrefs/JetPref. On other platforms, it can wrap UserDefaults, etc.
 */
interface PreferencesRepository {
    // Whisper Cloud settings
    fun getOpenaiApiKey(): String
    fun getWhisperLanguage(): String
    fun getWhisperPrompt(): String
    fun getWhisperTransliterateToRoman(): Boolean

    // Sarvam Cloud settings
    fun getSarvamApiKey(): String
    fun getSarvamMode(): String
    fun getSarvamLanguage(): String

    // Model ordering
    fun getModelsOrder(): List<InstalledModelReference>
    fun setModelsOrder(models: List<InstalledModelReference>)

    // Last selected model
    fun getLastSelectedModelPath(): String
    fun setLastSelectedModelPath(path: String)
}
