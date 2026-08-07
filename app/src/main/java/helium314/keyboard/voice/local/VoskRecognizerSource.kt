// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Model
import org.vosk.Recognizer as NativeVoskRecognizer
import java.util.Locale

internal data class LoadedVoskRuntime(
    val model: AutoCloseable,
    val recognizer: VoskRecognizerAdapter,
)

internal fun interface VoskRuntimeLoader {
    fun load(modelDirectory: String, languageCode: String): LoadedVoskRuntime
}

private object NativeVoskRuntimeLoader : VoskRuntimeLoader {
    override fun load(modelDirectory: String, languageCode: String): LoadedVoskRuntime {
        var model: Model? = null
        try {
            model = Model(modelDirectory)
            val nativeRecognizer = NativeVoskRecognizer(
                model,
                VoskRecognizerAdapter.SAMPLE_RATE_HZ,
            )
            return LoadedVoskRuntime(
                model = model,
                recognizer = VoskRecognizerAdapter(nativeRecognizer, languageCode),
            )
        } catch (failure: Exception) {
            model?.close()
            throw failure
        }
    }
}

/** A streaming [RecognizerSource] for a verified, installed Vosk model. */
class VoskRecognizerSource internal constructor(
    private val installedModel: InstalledLocalModel,
    private val runtimeLoader: VoskRuntimeLoader,
) : RecognizerSource {
    constructor(installedModel: InstalledLocalModel) : this(installedModel, NativeVoskRuntimeLoader)

    private val lifecycleLock = Any()
    private val descriptor = installedModel.descriptor
    private val artifact = requireNotNull(descriptor.artifact) { "Vosk model must have an artifact" }
    private val displayLocale = Locale.forLanguageTag(descriptor.languageTags.first())

    private val mutableState = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = mutableState.asStateFlow()

    private var nativeModel: AutoCloseable? = null
    private var adapter: VoskRecognizerAdapter? = null
    private var lastError = ""

    init {
        require(descriptor.runtime == LocalSpeechRuntime.VOSK) { "Expected a Vosk model" }
        require(descriptor.audioOwnership == AudioOwnership.APP_PCM) {
            "Vosk must use app-owned PCM capture"
        }
    }

    override val recognizer: Recognizer
        get() = synchronized(lifecycleLock) {
            checkNotNull(adapter) { "Vosk recognizer is not initialized" }
        }

    override val addSpaces: Boolean = true
    override val isBatchRecognizer: Boolean = false
    override val closed: Boolean get() = synchronized(lifecycleLock) { adapter == null }
    override val errorMessage: String get() = synchronized(lifecycleLock) { lastError }
    override val name: String get() = descriptor.displayName
    override val locale: SpeakKeysLocale = SpeakKeysLocale(
        language = displayLocale.language,
        country = displayLocale.country,
        variant = displayLocale.variant,
    )

    override suspend fun initialize() {
        synchronized(lifecycleLock) {
            if (adapter != null) {
                mutableState.value = RecognizerState.READY
                return
            }
            mutableState.value = RecognizerState.LOADING
            lastError = ""

            if (!LocalModelInstallMarker.matches(installedModel.directory, descriptor)) {
                lastError = "The downloaded speech model is missing or damaged"
                mutableState.value = RecognizerState.ERROR
                return
            }

            try {
                val loadedRuntime = runtimeLoader.load(
                    installedModel.directory.absolutePath,
                    locale.language,
                )
                nativeModel = loadedRuntime.model
                adapter = loadedRuntime.recognizer
                mutableState.value = RecognizerState.READY
            } catch (failure: Exception) {
                nativeModel = null
                adapter = null
                lastError = failure.message?.takeIf(String::isNotBlank)
                    ?: "Could not load the downloaded speech model"
                mutableState.value = RecognizerState.ERROR
            }
        }
    }

    override fun close(freeRAM: Boolean) {
        synchronized(lifecycleLock) {
            if (freeRAM) {
                adapter?.close()
                adapter = null
                nativeModel?.close()
                nativeModel = null
                mutableState.value = RecognizerState.CLOSED
            } else {
                // MySpeechService resets at the start of every utterance. Resetting here is both
                // redundant and unsafe: ModelManager exposes UI-idle before this asynchronous
                // close(false) runs, so a quick next utterance may already be feeding native Vosk.
                if (adapter != null) mutableState.value = RecognizerState.IN_RAM
            }
        }
    }
}
