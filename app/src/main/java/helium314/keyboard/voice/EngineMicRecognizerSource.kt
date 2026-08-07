// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.RecognitionListener
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import helium314.keyboard.voice.local.AndroidOnDeviceRecognitionCallback
import helium314.keyboard.voice.local.AndroidOnDeviceRecognitionConfig
import helium314.keyboard.voice.local.AndroidOnDeviceRecognitionError
import helium314.keyboard.voice.local.AndroidOnDeviceRecognitionState
import helium314.keyboard.voice.local.AndroidOnDeviceRecognizerController
import helium314.keyboard.voice.local.AndroidRecognitionHypothesis
import helium314.keyboard.voice.local.AndroidOnDeviceCapabilityStore
import helium314.keyboard.voice.local.CachedAndroidOnDeviceHindiCapability
import helium314.keyboard.voice.local.verifyAndroidOnDeviceHindiCapability
import helium314.keyboard.voice.local.shouldEnableAndroidLanguageSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * A recognition source that owns microphone capture instead of accepting SpeakKeys PCM.
 * ModelManager checks this interface before constructing [MySpeechService].
 */
interface EngineMicRecognizerSource : RecognizerSource {
    fun startListening(listener: RecognitionListener): Boolean
    fun stopListening(): Boolean
    fun cancelListening()
}

/** Android API 31+ on-device speech wrapped as a normal SpeakKeys source. */
class AndroidOnDeviceRecognizerSource(
    context: Context,
    private val config: AndroidOnDeviceRecognitionConfig = AndroidOnDeviceRecognitionConfig(),
    private val onCapabilityInvalidated: () -> Unit = {},
) : EngineMicRecognizerSource {
    private val applicationContext = context.applicationContext
    private val capabilityStore = AndroidOnDeviceCapabilityStore(applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(RecognizerState.NONE)
    override val stateFlow: StateFlow<RecognizerState> = mutableState.asStateFlow()

    @Volatile
    private var listener: RecognitionListener? = null
    @Volatile
    private var initialized = false
    private var controller: AndroidOnDeviceRecognizerController? = null
    private var finishRequested = false
    private var pendingFinalText: String? = null
    private var lastError = ""

    private val callback = object : AndroidOnDeviceRecognitionCallback {
        override fun onStateChanged(state: AndroidOnDeviceRecognitionState) {
            mutableState.value = when (state) {
                AndroidOnDeviceRecognitionState.LISTENING,
                AndroidOnDeviceRecognitionState.PROCESSING,
                -> RecognizerState.IN_RAM
                AndroidOnDeviceRecognitionState.IDLE -> RecognizerState.READY
                AndroidOnDeviceRecognitionState.UNAVAILABLE -> RecognizerState.ERROR
                AndroidOnDeviceRecognitionState.DESTROYED -> RecognizerState.CLOSED
            }
        }

        override fun onPartialResult(result: AndroidRecognitionHypothesis) {
            listener?.onPartialResult(result.text)
        }

        override fun onFinalResult(result: AndroidRecognitionHypothesis) {
            // Android may endpoint on silence before push-to-talk release. Keep that
            // result private until ModelManager explicitly requests finish.
            if (finishRequested) deliverFinal(result.text)
            else pendingFinalText = result.text
        }

        override fun onError(error: AndroidOnDeviceRecognitionError) {
            lastError = "Phone offline speech error: ${error.category.name.lowercase()}"
            val activeListener = listener
            listener = null
            pendingFinalText = null
            finishRequested = false
            activeListener?.onError(IllegalStateException(lastError))
        }
    }

    override val recognizer: Recognizer
        get() = error("Android on-device speech owns the microphone and has no PCM recognizer")
    override val addSpaces: Boolean = true
    override val isBatchRecognizer: Boolean = false
    override val closed: Boolean get() = !initialized
    override val errorMessage: String
        get() = lastError.ifBlank { "Offline speech is not available on this phone" }
    override val name: String = "Phone's offline speech"
    override val locale: SpeakKeysLocale = SpeakKeysLocale("hi", "IN")

    override suspend fun initialize() {
        mutableState.value = RecognizerState.LOADING
        initialized = false
        lastError = ""
        val serviceAvailable = withContext(Dispatchers.Main.immediate) {
            AndroidOnDeviceRecognizerController.isAvailable(applicationContext)
        }
        if (!serviceAvailable) {
            capabilityStore.recordHindiCapability(CachedAndroidOnDeviceHindiCapability.MISSING)
            onCapabilityInvalidated()
            lastError = "Offline speech is not available on this phone"
            mutableState.value = RecognizerState.ERROR
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (val capability = verifyAndroidOnDeviceHindiCapability(applicationContext)) {
                CachedAndroidOnDeviceHindiCapability.INSTALLED ->
                    capabilityStore.recordHindiCapability(capability)

                CachedAndroidOnDeviceHindiCapability.MISSING -> {
                    capabilityStore.recordHindiCapability(capability)
                    onCapabilityInvalidated()
                    lastError = "Offline Hindi is not installed on this phone"
                    mutableState.value = RecognizerState.ERROR
                    return
                }

                CachedAndroidOnDeviceHindiCapability.UNKNOWN -> {
                    lastError = "Could not verify offline Hindi on this phone"
                    mutableState.value = RecognizerState.ERROR
                    return
                }
            }
        }

        initialized = true
        mutableState.value = RecognizerState.READY
    }

    override fun startListening(listener: RecognitionListener): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!initialized) return false
        this.listener = listener
        finishRequested = false
        pendingFinalText = null
        val activeController = controller ?: AndroidOnDeviceRecognizerController(
            applicationContext,
            callback,
        ).also { controller = it }
        val effectiveConfig = config.copy(
            enableLanguageSwitch = shouldEnableAndroidLanguageSwitch(
                sdkInt = Build.VERSION.SDK_INT,
                requested = config.enableLanguageSwitch,
                hindiCapability = capabilityStore.hindiCapability(),
                englishCapability = capabilityStore.englishCapability(),
            ),
        )
        return activeController.start(effectiveConfig).also { started ->
            if (!started) this.listener = null
        }
    }

    override fun stopListening(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        finishRequested = true
        pendingFinalText?.let {
            pendingFinalText = null
            deliverFinal(it)
            return true
        }
        return controller?.stop() ?: false
    }

    override fun cancelListening() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener = null
            finishRequested = false
            pendingFinalText = null
            controller?.cancel()
        } else {
            mainHandler.post {
                listener = null
                finishRequested = false
                pendingFinalText = null
                controller?.cancel()
            }
        }
    }

    override fun close(freeRAM: Boolean) {
        if (!freeRAM) return
        val closeOnMain = {
            listener = null
            finishRequested = false
            pendingFinalText = null
            controller?.close()
            controller = null
            initialized = false
            mutableState.value = RecognizerState.CLOSED
        }
        if (Looper.myLooper() == Looper.getMainLooper()) closeOnMain() else mainHandler.post(closeOnMain)
    }

    private fun deliverFinal(text: String) {
        val activeListener = listener
        listener = null
        finishRequested = false
        pendingFinalText = null
        activeListener?.onFinalResult(text)
    }
}
