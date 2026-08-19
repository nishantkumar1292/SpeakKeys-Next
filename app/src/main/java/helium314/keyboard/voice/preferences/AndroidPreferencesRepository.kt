package helium314.keyboard.voice.preferences

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import helium314.keyboard.voice.AppCtx
import helium314.keyboard.voice.VoicePrefs
import helium314.keyboard.voice.credentials.VoiceCredentialService
import helium314.keyboard.voice.credentials.VoiceCredentialVault
import helium314.keyboard.voice.setModelsOrder
import helium314.keyboard.voice.speakKeysPreferenceModel

class AndroidPreferencesRepository : PreferencesRepository {
    private val prefs by speakKeysPreferenceModel()

    override fun getOpenaiApiKey(): String = credential(VoiceCredentialService.OPENAI)
    override fun getWhisperLanguage(): String = prefs.whisperLanguage.get()
    override fun getWhisperPrompt(): String = prefs.whisperPrompt.get()
    override fun getWhisperTransliterateToRoman(): Boolean = prefs.whisperTransliterateToRoman.get()

    override fun getSarvamApiKey(): String = credential(VoiceCredentialService.SARVAM)
    override fun getSarvamMode(): String = prefs.sarvamMode.get()
    override fun getSarvamLanguage(): String = prefs.sarvamLanguage.get()
    override fun getElevenLabsApiKey(): String = credential(VoiceCredentialService.ELEVENLABS)
    override fun getVoiceOutputStyle(): String = prefs.voiceOutputStyle.get()

    override fun getModelsOrder(): List<InstalledModelReference> = prefs.modelsOrder.get()
    override fun setModelsOrder(models: List<InstalledModelReference>) = prefs.setModelsOrder(models)

    override fun getLastSelectedModelPath(): String = prefs.lastSelectedModelPath.get()
    override fun setLastSelectedModelPath(path: String) = prefs.lastSelectedModelPath.set(path)

    private fun credential(service: VoiceCredentialService): String = AppCtx.appCtx
        ?.let { VoiceCredentialVault.get(it).credential(service) }
        .orEmpty()
}
