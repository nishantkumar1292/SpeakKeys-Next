package helium314.keyboard.voice.preferences

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import helium314.keyboard.voice.VoicePrefs
import helium314.keyboard.voice.speakKeysPreferenceModel

class AndroidPreferencesRepository : PreferencesRepository {
    private val prefs by speakKeysPreferenceModel()

    override fun getOpenaiApiKey(): String = prefs.openaiApiKey.get()
    override fun getWhisperLanguage(): String = prefs.whisperLanguage.get()
    override fun getWhisperPrompt(): String = prefs.whisperPrompt.get()
    override fun getWhisperTransliterateToRoman(): Boolean = prefs.whisperTransliterateToRoman.get()

    override fun getSarvamApiKey(): String = prefs.sarvamApiKey.get()
    override fun getSarvamMode(): String = prefs.sarvamMode.get()
    override fun getSarvamLanguage(): String = prefs.sarvamLanguage.get()

    override fun getModelsOrder(): List<InstalledModelReference> = prefs.modelsOrder.get()
    override fun setModelsOrder(models: List<InstalledModelReference>) = prefs.modelsOrder.set(models)

    override fun getLastSelectedModelPath(): String = prefs.lastSelectedModelPath.get()
    override fun setLastSelectedModelPath(path: String) = prefs.lastSelectedModelPath.set(path)
}
