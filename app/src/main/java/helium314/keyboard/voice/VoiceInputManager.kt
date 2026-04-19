package helium314.keyboard.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.app.ActivityCompat
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.recognition.text.TextProcessor
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.voice.auth.AndroidAuthTokenProvider
import helium314.keyboard.voice.preferences.AndroidPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceInputManager(
    private val latinIME: LatinIME
) : ModelManager.Listener {

    interface StateListener {
        fun onVoiceIdle()
        fun onVoiceError(message: String)
    }

    private val prefs by speakKeysPreferenceModel()

    val lifecycleOwner = IMELifecycleOwner()

    private lateinit var modelManager: ModelManager
    private var currentRecognizerSource: RecognizerSource? = null
    private var stateFlowJob: Job? = null
    private var authRetryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var hasMicPermission = false
    private var listening = false
    private var processing = false
    private var commitOnFinish = true
    private val deferredResults = mutableListOf<String>()

    private var stateListener: StateListener? = null

    val isListening: Boolean get() = listening

    private val uiHandler = Handler(Looper.getMainLooper())
    private val holdAutoStopRunnable = Runnable {
        if (listening && modelManager.isRunning) {
            // Auto-stop on hold timeout: commit and notify bar to return to idle.
            stopListening(commit = true)
            stateListener?.onVoiceIdle()
        }
    }

    fun setStateListener(listener: StateListener?) {
        stateListener = listener
    }

    fun onCreate() {
        lifecycleOwner.onCreate()
        HapticHelper.init(latinIME)
        checkMicrophonePermission()
        modelManager = ModelManager(
            latinIME,
            this,
            AndroidPreferencesRepository(),
            AndroidAuthTokenProvider()
        )
        modelManager.initializeFirstLocale(false)
    }

    fun onWindowShown() {
        lifecycleOwner.onResume()
    }

    fun onWindowHidden() {
        lifecycleOwner.onPause()
    }

    fun onStartInputView(editorInfo: EditorInfo) {
        lifecycleOwner.attachToDecorView(latinIME.window?.window?.decorView)
        checkMicrophonePermission()
        modelManager.reloadModels()
        if (currentRecognizerSource == null) {
            modelManager.initializeFirstLocale(false)
        }
    }

    fun onFinishInputView() {
        abortListening()
    }

    fun onDestroy() {
        abortListening()
        stateFlowJob?.cancel()
        authRetryJob?.cancel()
        lifecycleOwner.onDestroy()
        modelManager.onDestroy()
    }

    fun startListening() {
        if (processing || listening) return
        if (!hasMicPermission) {
            latinIME.startActivity(PermissionRequestActivity.createIntent(latinIME))
            stateListener?.onVoiceError(latinIME.getString(R.string.mic_error_no_permission))
            return
        }
        if (modelManager.openSettingsOnMic) {
            stateListener?.onVoiceError(latinIME.getString(R.string.mic_error_no_recognizers))
            return
        }
        listening = true
        commitOnFinish = true
        deferredResults.clear()
        if (!modelManager.isRunning) {
            modelManager.start()
        }
        scheduleHoldTimers()
    }

    fun stopListening(commit: Boolean) {
        if (!listening) return
        listening = false
        commitOnFinish = commit
        cancelHoldTimers()
        if (modelManager.isRunning) {
            processing = true
            modelManager.stop()
        } else {
            processing = false
            deferredResults.clear()
        }
    }

    private fun abortListening() {
        listening = false
        commitOnFinish = false
        cancelHoldTimers()
        if (modelManager.isRunning) {
            modelManager.stop()
        }
        processing = false
        deferredResults.clear()
    }

    override fun onResult(text: String?) {
        val chunk = text?.trim().orEmpty()
        if (chunk.isNotEmpty() && (deferredResults.isEmpty() || deferredResults.last() != chunk)) {
            deferredResults.add(chunk)
        }
    }

    override fun onFinalResult(text: String?) {
        val shouldCommit = commitOnFinish
        processing = false
        val finalText = mergeDeferredResults(text)
        if (shouldCommit && finalText.isNotEmpty()) {
            commitVoiceText(finalText)
        }
    }

    override fun onPartialResult(partialText: String?) {
        // Push-to-talk hides partials while holding.
    }

    override fun onStateChanged(state: ModelManager.State) {
        if (state != ModelManager.State.STATE_LISTENING) {
            cancelHoldTimers()
        }
        if (state == ModelManager.State.STATE_STOPPED) {
            stateFlowJob?.cancel()
        }
    }

    override fun onError(type: ModelManager.ErrorType) {
        val msg = when (type) {
            ModelManager.ErrorType.MIC_IN_USE -> latinIME.getString(R.string.mic_error_mic_in_use)
            ModelManager.ErrorType.NO_RECOGNIZERS_INSTALLED -> latinIME.getString(R.string.mic_error_no_recognizers)
        }
        failToIdle(msg)
    }

    override fun onError(e: Exception?) {
        failToIdle(latinIME.getString(R.string.mic_error_recognizer_error))
    }

    override fun onRecognizerSource(source: RecognizerSource) {
        stateFlowJob?.cancel()
        authRetryJob?.cancel()
        currentRecognizerSource = source
        stateFlowJob = scope.launch {
            source.stateFlow.collect { state ->
                if (state == RecognizerState.ERROR) {
                    stateListener?.onVoiceError(source.errorMessage)
                    scheduleAuthRetry()
                }
            }
        }
    }

    override fun onTimeout() {
        failToIdle(null)
    }

    private fun failToIdle(message: String?) {
        listening = false
        processing = false
        deferredResults.clear()
        cancelHoldTimers()
        if (message != null) stateListener?.onVoiceError(message)
        stateListener?.onVoiceIdle()
    }

    private fun commitVoiceText(text: String) {
        val ic = latinIME.currentInputConnection ?: return
        val textBeforeCursor = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
        val processedText = TextProcessor.processText(
            text = text,
            shouldCapitalize = TextProcessor.capitalizeAfter(textBeforeCursor) ?: false,
            shouldAddSpace = modelManager.currentRecognizerSourceAddSpaces &&
                textBeforeCursor.isNotEmpty() &&
                TextProcessor.addSpaceAfter(textBeforeCursor.last()),
            autoCapitalizeEnabled = prefs.logicAutoCapitalize.get(),
            recognizerAddsSpaces = modelManager.currentRecognizerSourceAddSpaces
        )
        latinIME.commitVoiceResult(processedText)
    }

    private fun checkMicrophonePermission() {
        hasMicPermission = ActivityCompat.checkSelfPermission(
            latinIME,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleAuthRetry() {
        authRetryJob?.cancel()
        authRetryJob = scope.launch {
            delay(5000)
            if (!listening && !processing) {
                Log.d(TAG, "Auto-retrying initialization after auth error")
                modelManager.initializeFirstLocale(false)
            }
        }
    }

    private fun scheduleHoldTimers() {
        cancelHoldTimers()
        uiHandler.postDelayed(holdAutoStopRunnable, HOLD_AUTO_STOP_MS)
    }

    private fun cancelHoldTimers() {
        uiHandler.removeCallbacks(holdAutoStopRunnable)
    }

    private fun mergeDeferredResults(finalText: String?): String {
        val parts = deferredResults.toMutableList()
        deferredResults.clear()
        val tail = finalText?.trim().orEmpty()
        if (tail.isNotEmpty() && (parts.isEmpty() || parts.last() != tail)) {
            parts.add(tail)
        }
        if (parts.isEmpty()) return ""
        return if (modelManager.currentRecognizerSourceAddSpaces) {
            parts.joinToString(" ").trim()
        } else {
            parts.joinToString("").trim()
        }
    }

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val HOLD_AUTO_STOP_MS = 30_000L
    }
}
