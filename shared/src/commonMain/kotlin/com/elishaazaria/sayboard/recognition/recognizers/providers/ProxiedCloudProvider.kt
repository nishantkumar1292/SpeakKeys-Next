package com.elishaazaria.sayboard.recognition.recognizers.providers

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.logging.Logger
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.sources.ProxiedCloud

class ProxiedCloudProvider(
    private val prefs: PreferencesRepository,
    private val authTokenProvider: AuthTokenProvider
) : RecognizerSourceProvider {

    companion object {
        private const val TAG = "ProxiedCloudProvider"
    }

    override fun getInstalledModels(): List<InstalledModelReference> {
        if (!authTokenProvider.isSignedIn) {
            Logger.d(TAG, "getInstalledModels: not signed in, skipping proxied models")
            return emptyList()
        }
        Logger.d(TAG, "getInstalledModels: signed in as ${authTokenProvider.userEmail}, returning proxied models")

        return listOf(
            InstalledModelReference(
                path = "proxied://sarvam",
                name = "Sarvam Cloud (Proxied)",
                type = ModelType.ProxiedSarvamCloud
            )
        )
    }

    override fun recognizerSourceForModel(localModel: InstalledModelReference): RecognizerSource? {
        if (localModel.type != ModelType.ProxiedSarvamCloud) {
            return null
        }

        if (!authTokenProvider.isSignedIn) return null

        return when (localModel.type) {
            ModelType.ProxiedSarvamCloud -> {
                val locale = SpeakKeysLocale("en", "IN")
                val mode = normalizeSarvamMode(prefs.getSarvamMode())
                val languageCode = prefs.getSarvamLanguage()

                val params = mapOf(
                    "model" to "saaras:v3",
                    "mode" to mode,
                    "language_code" to languageCode
                )

                ProxiedCloud(authTokenProvider, "sarvam", locale, params)
            }
            else -> null
        }
    }

    private fun normalizeSarvamMode(mode: String): String = when (mode) {
        "native" -> "transcribe"
        "transcribe", "translit" -> mode
        else -> "translit"
    }
}
