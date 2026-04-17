package com.elishaazaria.sayboard.recognition.recognizers.providers

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource

class Providers(prefs: PreferencesRepository, authTokenProvider: AuthTokenProvider) {
    private val whisperCloudProvider: WhisperCloudProvider
    private val sarvamCloudProvider: SarvamCloudProvider
    private val proxiedCloudProvider: ProxiedCloudProvider
    private val providers: List<RecognizerSourceProvider>

    init {
        val providersM = mutableListOf<RecognizerSourceProvider>()
        sarvamCloudProvider = SarvamCloudProvider(prefs)
        providersM.add(sarvamCloudProvider)
        whisperCloudProvider = WhisperCloudProvider(prefs)
        providersM.add(whisperCloudProvider)
        proxiedCloudProvider = ProxiedCloudProvider(prefs, authTokenProvider)
        providersM.add(proxiedCloudProvider)
        providers = providersM
    }

    fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        return when (localModel.type) {
            ModelType.WhisperCloud -> whisperCloudProvider.recognizerSourceForModel(localModel)
            ModelType.SarvamCloud -> sarvamCloudProvider.recognizerSourceForModel(localModel)
            ModelType.ProxiedWhisperCloud,
            ModelType.ProxiedSarvamCloud -> proxiedCloudProvider.recognizerSourceForModel(localModel)
        }
    }

    fun installedModels(): Collection<InstalledModelReference> {
        return providers.map { it.getInstalledModels() }.flatten()
    }
}
