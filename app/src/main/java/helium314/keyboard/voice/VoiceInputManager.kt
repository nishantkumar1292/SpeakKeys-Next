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
import com.elishaazaria.sayboard.recognition.recognizers.RecoverableAuthFailureSource
import com.elishaazaria.sayboard.recognition.text.TextProcessor
import com.elishaazaria.sayboard.utils.DevanagariTransliterator
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.voice.auth.AndroidAuthTokenProvider
import helium314.keyboard.voice.credentials.VoiceCredentialVault
import helium314.keyboard.voice.preferences.AndroidPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceInputManager(
    private val latinIME: LatinIME
) : ModelManager.Listener {

    interface StateListener {
        fun onVoiceIdle()
        fun onVoiceProcessing()
        fun onVoiceError(message: String)

        /** Latest provisional transcript. Each value replaces the previous one. */
        fun onVoicePartial(text: String) {}
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
    private var editorAllowsVoiceInput = false
    private val deferredResults = mutableListOf<String>()
    private var lastPartialText = ""
    private val latencyTracker = VoiceLatencyTracker()

    private var stateListener: StateListener? = null

    val isListening: Boolean get() = listening

    private val uiHandler = Handler(Looper.getMainLooper())
    private val holdAutoStopRunnable = Runnable {
        if (listening && modelManager.isRunning) {
            // Auto-stop on hold timeout: commit. stopListening fires onVoiceProcessing;
            // onFinalResult fires onVoiceIdle once the backend returns.
            stopListening(commit = true)
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
            AndroidAuthTokenProvider(),
            canPersistProviderCatalog = {
                VoiceCredentialVault.get(latinIME).configuration().let { configuration ->
                    configuration.storageAvailable && !configuration.storageError
                }
            },
        )
    }

    fun onWindowShown() {
        lifecycleOwner.onResume()
    }

    fun onWindowHidden() {
        editorAllowsVoiceInput = false
        abortListening()
        lifecycleOwner.onPause()
    }

    /** Lightweight policy update used even when no keyboard view exists (for example rotation). */
    fun onEditorVoiceEligibilityChanged(allowed: Boolean) {
        editorAllowsVoiceInput = allowed
        if (!allowed && (listening || processing || modelManager.isRunning)) {
            abortListening()
        }
    }

    fun onStartInputView(editorInfo: EditorInfo) {
        onEditorVoiceEligibilityChanged(isEditorSafeForVoice(editorInfo))
        lifecycleOwner.attachToDecorView(latinIME.window?.window?.decorView)
        checkMicrophonePermission()
        modelManager.reloadModels()
        // reloadModels rebuilds sources so key, output-style, and download changes
        // take effect even when the stable model ID did not change.
        modelManager.initializeFirstLocale(false)
    }

    fun onFinishInputView() {
        editorAllowsVoiceInput = false
        abortListening()
    }

    fun onDestroy() {
        abortListening()
        stateFlowJob?.cancel()
        authRetryJob?.cancel()
        lifecycleOwner.onDestroy()
        modelManager.onDestroy()
        scope.cancel()
    }

    /** Releases large native local models when Android reports memory pressure between utterances. */
    fun onTrimMemory() {
        if (!listening && !processing) modelManager.cancel(forceFreeRam = true)
    }

    fun startListening() {
        if (processing || listening) return
        val currentEditor = latinIME.currentInputEditorInfo ?: return
        editorAllowsVoiceInput = isEditorSafeForVoice(currentEditor)
        if (!editorAllowsVoiceInput) return
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
        processing = false
        commitOnFinish = true
        deferredResults.clear()
        clearPartialResult()
        latencyTracker.onMicStarted()
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
        clearPartialResult()
        if (!commit) {
            latencyTracker.onCancelled()
            modelManager.cancel()
            processing = false
            deferredResults.clear()
            stateListener?.onVoiceIdle()
        } else if (modelManager.isRunning) {
            latencyTracker.onReleased()
            processing = true
            modelManager.stop()
            // An OEM SpeechRecognizer may synchronously deliver its final result from stop().
            // onFinalResult clears processing and publishes idle, which must not be overwritten.
            if (processing) stateListener?.onVoiceProcessing()
        } else {
            // A heavyweight local model may still be prewarming, or a replacement AudioRecord may
            // be waiting for cleanup. Releasing before capture starts cancels that deferred start.
            modelManager.cancel()
            latencyTracker.onCancelled()
            processing = false
            deferredResults.clear()
            stateListener?.onVoiceIdle()
        }
    }

    /** Cancels recording or pending recognition and guarantees that no result will be committed. */
    fun cancelListening() {
        abortListening()
    }

    private fun abortListening() {
        listening = false
        commitOnFinish = false
        cancelHoldTimers()
        clearPartialResult()
        latencyTracker.onCancelled()
        modelManager.cancel()
        processing = false
        deferredResults.clear()
        stateListener?.onVoiceIdle()
    }

    override fun onResult(text: String?) {
        val chunk = text?.trim().orEmpty()
        if (chunk.isNotEmpty() && (deferredResults.isEmpty() || deferredResults.last() != chunk)) {
            deferredResults.add(chunk)
        }
    }

    override fun onFinalResult(text: String?) {
        val shouldCommit = commitOnFinish
        listening = false
        processing = false
        cancelHoldTimers()
        clearPartialResult()
        val finalText = applyOutputStyle(mergeDeferredResults(text))
        latencyTracker.onFinal()?.let { sample ->
            Log.d(
                TAG,
                "Voice latency #${sample.utteranceId}: " +
                    "micToPartial=${sample.micToFirstPartialMs ?: -1}ms, " +
                    "releaseToFinal=${sample.releaseToFinalMs ?: -1}ms, total=${sample.totalMs}ms",
            )
        }
        if (shouldCommit && editorAllowsVoiceInput && isCurrentEditorSafeForVoice()
            && finalText.isNotEmpty()
        ) {
            commitVoiceText(finalText)
        }
        stateListener?.onVoiceIdle()
    }

    override fun onPartialResult(partialText: String?) {
        if (!listening) return
        val replacement = applyOutputStyle(partialText?.trim().orEmpty())
        if (replacement == lastPartialText) return
        if (replacement.isNotEmpty()) latencyTracker.onFirstPartial()
        lastPartialText = replacement
        stateListener?.onVoicePartial(replacement)
    }

    override fun onStateChanged(state: ModelManager.State) {
        if (state != ModelManager.State.STATE_LISTENING) {
            cancelHoldTimers()
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
                    // A start-in-progress gets its terminal callback from ModelManager, which also
                    // returns the UI to idle. Prewarm failures still need a visible explanation.
                    if (!listening && !processing) {
                        stateListener?.onVoiceError(source.errorMessage)
                    }
                    if (shouldRetryRecognizerInitialization(source)) {
                        scheduleAuthRetry(source)
                    } else {
                        authRetryJob?.cancel()
                        authRetryJob = null
                    }
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
        commitOnFinish = false
        latencyTracker.onCancelled()
        deferredResults.clear()
        clearPartialResult()
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

    private fun isCurrentEditorSafeForVoice(): Boolean =
        latinIME.currentInputEditorInfo?.let(::isEditorSafeForVoice) == true

    private fun isEditorSafeForVoice(editorInfo: EditorInfo): Boolean =
        InputAttributes.shouldAllowSpeakKeysVoiceInput(editorInfo, latinIME.packageName)

    private fun scheduleAuthRetry(source: RecognizerSource) {
        authRetryJob?.cancel()
        authRetryJob = scope.launch {
            delay(5000)
            if (
                currentRecognizerSource === source &&
                shouldRetryRecognizerInitialization(source) &&
                !listening &&
                !processing
            ) {
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

    private fun clearPartialResult() {
        if (lastPartialText.isEmpty()) return
        lastPartialText = ""
        stateListener?.onVoicePartial("")
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

    private fun applyOutputStyle(text: String): String =
        if (text.isNotEmpty() && prefs.voiceOutputStyle.get() == OUTPUT_STYLE_LATIN) {
            DevanagariTransliterator.transliterate(text)
        } else {
            text
        }

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val HOLD_AUTO_STOP_MS = 30_000L
        private const val OUTPUT_STYLE_LATIN = "latin"
    }
}

internal fun shouldRetryRecognizerInitialization(source: RecognizerSource): Boolean =
    source is RecoverableAuthFailureSource && source.hasRecoverableAuthFailure
