// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.voice.local.AndroidOnDeviceRecognizerController
import helium314.keyboard.voice.local.AndroidOnDeviceCapabilityStore
import helium314.keyboard.voice.local.LocalModelInstaller
import helium314.keyboard.voice.local.LocalSpeechModelCatalog
import helium314.keyboard.voice.local.LocalSpeechRuntime
import helium314.keyboard.voice.local.VoskRecognizerSource
import helium314.keyboard.voice.local.shouldRegisterAndroidOnDeviceRecognizer
import helium314.keyboard.voice.streaming.ElevenLabsRealtimeRecognizerSource
import helium314.keyboard.voice.streaming.SarvamRealtimeRecognizerSource
import helium314.keyboard.voice.streaming.TaraRealtimeRecognizerSource

/**
 * Android-side provider registry. Shared KMP cloud providers remain usable, while Android-only
 * realtime and local engines can be added without teaching common code about native runtimes.
 */
class AndroidRecognizerProviders(
    context: Context,
    private val prefs: PreferencesRepository,
    private val authTokenProvider: AuthTokenProvider,
    private val canPersistProviderCatalog: () -> Boolean = { true },
    private val taraRealtimeEndpoint: String = BuildConfig.TARA_REALTIME_ENDPOINT,
) {
    private val applicationContext = context.applicationContext
    private val sharedProviders = Providers(prefs, authTokenProvider)
    private val localInstaller = LocalModelInstaller(applicationContext)
    private val androidOnDeviceCapability = AndroidOnDeviceCapabilityStore(applicationContext)

    fun installedModels(): List<InstalledModelReference> {
        val shared = sharedProviders.installedModels().associateBy { it.type }
        return buildList {
            if (taraRealtimeEndpoint.isNotBlank() && authTokenProvider.isSignedIn) {
                add(
                    InstalledModelReference(
                        path = TARA_REALTIME_PATH,
                        name = "Natural Hinglish",
                        // Tara is Whisper-large-v3 compatible. Reusing this persisted family keeps
                        // preference JSON readable by older SpeakKeys builds during rollback.
                        type = ModelType.ProxiedWhisperCloud,
                    ),
                )
            }
            shared[ModelType.SarvamCloud]?.let(::add)
            if (prefs.getElevenLabsApiKey().isNotBlank()) {
                add(
                    InstalledModelReference(
                        path = ELEVENLABS_REALTIME_PATH,
                        name = "Detailed Hindi + English",
                        type = ModelType.ElevenLabsCloud,
                    ),
                )
            }
            if (shouldExposeAndroidOnDeviceRecognizer()) {
                add(
                    InstalledModelReference(
                        path = LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID,
                        name = LocalSpeechModelCatalog.androidOnDevice.displayName,
                        type = ModelType.AndroidOnDevice,
                    ),
                )
            }
            localInstaller.installedModels().forEach { installed ->
                add(
                    InstalledModelReference(
                        path = installed.descriptor.stableId,
                        name = installed.descriptor.displayName,
                        type = ModelType.VoskLocal,
                    ),
                )
            }
            shared[ModelType.ProxiedSarvamCloud]?.let(::add)
            shared[ModelType.ProxiedWhisperCloud]?.let(::add)
            shared[ModelType.WhisperCloud]?.let(::add)
        }.distinctBy(InstalledModelReference::path)
    }

    fun recognizerSourceForModel(model: InstalledModelReference): RecognizerSource? {
        // A local model's stable catalog path identifies its language and version. Dispatching by
        // runtime lets the curated catalog grow without adding a persisted enum for every model.
        LocalSpeechModelCatalog.find(model.path)?.let { descriptor ->
            return when (descriptor.runtime) {
                LocalSpeechRuntime.ANDROID_ON_DEVICE -> if (
                    shouldExposeAndroidOnDeviceRecognizer()
                ) {
                    AndroidOnDeviceRecognizerSource(
                        applicationContext,
                        onCapabilityInvalidated = ::removeAndroidOnDeviceModelFromPreferences,
                    )
                } else {
                    null
                }
                LocalSpeechRuntime.VOSK ->
                    localInstaller.installedModel(model.path)?.let(::VoskRecognizerSource)
            }
        }

        if (model.path == TARA_REALTIME_PATH) {
            return if (taraRealtimeEndpoint.isNotBlank() && authTokenProvider.isSignedIn) {
                TaraRealtimeRecognizerSource(
                    tokenProvider = { forceRefresh ->
                        authTokenProvider.getIdToken(forceRefresh)
                    },
                    endpoint = taraRealtimeEndpoint,
                    displayLocale = SpeakKeysLocale("hi", "IN"),
                    transliterateToRoman = prefs.getVoiceOutputStyle() == OUTPUT_STYLE_LATIN,
                )
            } else {
                null
            }
        }

        return when (model.type) {
            ModelType.SarvamCloud -> prefs.getSarvamApiKey()
                .takeIf(String::isNotBlank)
                ?.let { apiKey ->
                    SarvamRealtimeRecognizerSource(
                        apiKey = apiKey,
                        displayLocale = SpeakKeysLocale("hi", "IN"),
                        sarvamLanguageCode = prefs.getSarvamLanguage()
                            .takeUnless { it.isBlank() || it == "unknown" }
                            ?: "auto",
                        mode = if (prefs.getVoiceOutputStyle() == OUTPUT_STYLE_LATIN) {
                            "translit"
                        } else {
                            "codemix"
                        },
                        streamType = "balanced",
                    )
                }

            ModelType.ElevenLabsCloud -> prefs.getElevenLabsApiKey()
                .takeIf(String::isNotBlank)
                ?.let { apiKey ->
                    ElevenLabsRealtimeRecognizerSource(
                        apiKey = apiKey,
                        displayLocale = SpeakKeysLocale("hi", "IN"),
                        languageCode = null,
                    )
                }

            ModelType.AndroidOnDevice,
            ModelType.VoskLocal,
            ModelType.VoskHindiLocal,
            ModelType.VoskEnglishIndiaLocal,
            -> null

            ModelType.WhisperCloud,
            ModelType.ProxiedWhisperCloud,
            ModelType.ProxiedSarvamCloud,
            -> sharedProviders.recognizerSourceForModel(model)
        }
    }

    private fun shouldExposeAndroidOnDeviceRecognizer(): Boolean {
        val serviceAvailable = runCatching {
            AndroidOnDeviceRecognizerController.isAvailable(applicationContext)
        }.getOrDefault(false)
        return shouldRegisterAndroidOnDeviceRecognizer(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            serviceAvailable = serviceAvailable,
            cachedHindiCapability = androidOnDeviceCapability.hindiCapability(),
            explicitlySelected = prefs.getLastSelectedModelPath() ==
                LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID,
        )
    }

    private fun removeAndroidOnDeviceModelFromPreferences() {
        persistProviderCatalogIfAuthoritative(canPersistProviderCatalog()) {
            val modelId = LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID
            val remaining = prefs.getModelsOrder().filterNot { it.path == modelId }
            prefs.setModelsOrder(remaining)
            if (prefs.getLastSelectedModelPath() == modelId) {
                prefs.setLastSelectedModelPath(remaining.firstOrNull()?.path.orEmpty())
            }
        }
    }

    companion object {
        const val ELEVENLABS_REALTIME_PATH = "elevenlabs://scribe-v2-realtime"
        const val TARA_REALTIME_PATH = "speakkeys://tara"
        const val OUTPUT_STYLE_LATIN = "latin"
    }
}
