package helium314.keyboard.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.core.app.ActivityCompat
import com.elishaazaria.sayboard.recognition.RecognitionListener
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
) : ModelManager.Listener, VoiceKeyboardView.Listener {

    private val prefs by speakKeysPreferenceModel()

    val lifecycleOwner = IMELifecycleOwner()

    private lateinit var modelManager: ModelManager
    private var voiceKeyboardView: VoiceKeyboardView? = null
    private var currentRecognizerSource: RecognizerSource? = null
    private var stateFlowJob: Job? = null
    private var authRetryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var hasMicPermission = false
    private var micPressed = false
    private var micProcessing = false
    private val deferredResults = mutableListOf<String>()

    private var enterActionLabel = ""
    private var enterActionVisual = VoiceKeyboardView.EnterActionVisual.ENTER

    var currentState = VoiceKeyboardView.STATE_INITIAL
        private set(value) {
            field = value
            voiceKeyboardView?.state = value
        }

    var errorMessage = ""
        private set(value) {
            field = value
            voiceKeyboardView?.errorMessage = value
        }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val holdWarningRunnable = Runnable {
        if (micPressed && modelManager.isRunning) {
            currentState = VoiceKeyboardView.STATE_LIMIT_WARNING
        }
    }
    private val holdAutoStopRunnable = Runnable {
        if (micPressed && modelManager.isRunning) {
            micPressed = false
            startProcessing()
            modelManager.stop()
        }
    }

    fun bindView(view: VoiceKeyboardView?) {
        voiceKeyboardView?.setListener(null)
        voiceKeyboardView = view
        voiceKeyboardView?.setListener(this)
        syncView()
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
        resolveEnterKey(editorInfo)
        checkMicrophonePermission()
        modelManager.reloadModels()
        if (currentRecognizerSource == null) {
            modelManager.initializeFirstLocale(false)
        } else if (hasMicPermission) {
            onRecognizerStateChanged(currentRecognizerSource!!.stateFlow.value)
        }
        syncView()
    }

    fun onFinishInputView() {
        micPressed = false
        micProcessing = false
        deferredResults.clear()
        cancelHoldTimers()
        modelManager.stop()
        currentState = VoiceKeyboardView.STATE_READY
    }

    fun onDestroy() {
        micPressed = false
        micProcessing = false
        deferredResults.clear()
        cancelHoldTimers()
        stateFlowJob?.cancel()
        authRetryJob?.cancel()
        lifecycleOwner.onDestroy()
        modelManager.onDestroy()
        bindView(null)
    }

    override fun micPressStart() {
        if (micProcessing) return
        if (!hasMicPermission) {
            latinIME.startActivity(PermissionRequestActivity.createIntent(latinIME))
            return
        }
        if (modelManager.openSettingsOnMic) {
            settingsClicked()
            return
        }
        micPressed = true
        deferredResults.clear()
        if (!modelManager.isRunning) {
            modelManager.start()
        }
    }

    override fun micPressEnd() {
        micPressed = false
        cancelHoldTimers()
        if (modelManager.isRunning) {
            startProcessing()
            modelManager.stop()
        }
    }

    override fun backspaceClicked() {
        latinIME.handleVoiceBackspace()
    }

    override fun settingsClicked() {
        latinIME.openVoiceSettings()
    }

    override fun buttonClicked(text: String) {
        latinIME.commitVoiceInlineText(text)
    }

    override fun toggleKeyboardMode() {
        latinIME.showTypingKeyboard()
    }

    override fun cursorLeftClicked() {
        latinIME.moveVoiceCursor(-1)
    }

    override fun cursorRightClicked() {
        latinIME.moveVoiceCursor(1)
    }

    override fun enterClicked() {
        latinIME.performVoiceEnterAction()
    }

    override fun onResult(text: String?) {
        val chunk = text?.trim().orEmpty()
        if (chunk.isNotEmpty() && (deferredResults.isEmpty() || deferredResults.last() != chunk)) {
            deferredResults.add(chunk)
        }
    }

    override fun onFinalResult(text: String?) {
        finishProcessing()
        val finalText = mergeDeferredResults(text)
        if (finalText.isEmpty()) return
        commitVoiceText(finalText)
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
            currentState = if (micProcessing) {
                VoiceKeyboardView.STATE_PROCESSING
            } else {
                VoiceKeyboardView.STATE_READY
            }
            return
        }

        currentState = when (state) {
            ModelManager.State.STATE_INITIAL -> VoiceKeyboardView.STATE_INITIAL
            ModelManager.State.STATE_LOADING -> VoiceKeyboardView.STATE_LOADING
            ModelManager.State.STATE_READY -> VoiceKeyboardView.STATE_READY
            ModelManager.State.STATE_LISTENING -> {
                if (micPressed) {
                    scheduleHoldTimers()
                    VoiceKeyboardView.STATE_LISTENING
                } else {
                    startProcessing()
                    modelManager.stop()
                    VoiceKeyboardView.STATE_PROCESSING
                }
            }
            ModelManager.State.STATE_PAUSED -> VoiceKeyboardView.STATE_PAUSED
            ModelManager.State.STATE_ERROR -> VoiceKeyboardView.STATE_ERROR
            ModelManager.State.STATE_STOPPED -> VoiceKeyboardView.STATE_READY
        }
    }

    override fun onError(type: ModelManager.ErrorType) {
        finishProcessing()
        deferredResults.clear()
        errorMessage = when (type) {
            ModelManager.ErrorType.MIC_IN_USE -> latinIME.getString(R.string.mic_error_mic_in_use)
            ModelManager.ErrorType.NO_RECOGNIZERS_INSTALLED -> latinIME.getString(R.string.mic_error_no_recognizers)
        }
        currentState = VoiceKeyboardView.STATE_ERROR
    }

    override fun onError(e: Exception?) {
        finishProcessing()
        deferredResults.clear()
        errorMessage = latinIME.getString(R.string.mic_error_recognizer_error)
        currentState = VoiceKeyboardView.STATE_ERROR
    }

    override fun onRecognizerSource(source: RecognizerSource) {
        stateFlowJob?.cancel()
        authRetryJob?.cancel()
        currentRecognizerSource = source
        stateFlowJob = scope.launch {
            source.stateFlow.collect { state ->
                onRecognizerStateChanged(state)
                if (state == RecognizerState.ERROR) {
                    errorMessage = source.errorMessage
                    scheduleAuthRetry()
                }
            }
        }
    }

    override fun onTimeout() {
        finishProcessing()
        deferredResults.clear()
        currentState = VoiceKeyboardView.STATE_READY
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

        if (!hasMicPermission) {
            errorMessage = latinIME.getString(R.string.mic_error_no_permission)
            currentState = VoiceKeyboardView.STATE_ERROR
        }
    }

    private fun onRecognizerStateChanged(value: RecognizerState) {
        if (!hasMicPermission) {
            return
        }
        currentState = when (value) {
            RecognizerState.CLOSED,
            RecognizerState.NONE -> VoiceKeyboardView.STATE_INITIAL
            RecognizerState.LOADING -> VoiceKeyboardView.STATE_LOADING
            RecognizerState.READY -> VoiceKeyboardView.STATE_READY
            RecognizerState.IN_RAM -> VoiceKeyboardView.STATE_PAUSED
            RecognizerState.ERROR -> VoiceKeyboardView.STATE_ERROR
        }
    }

    private fun resolveEnterKey(editorInfo: EditorInfo) {
        enterActionLabel = latinIME.getString(R.string.ime_action_enter)
        enterActionVisual = VoiceKeyboardView.EnterActionVisual.ENTER

        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            voiceKeyboardView?.actionLabel = enterActionLabel
            voiceKeyboardView?.actionVisual = enterActionVisual
            return
        }

        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val customLabel = editorInfo.actionLabel?.toString()?.trim().orEmpty()
        val customActionId = when {
            editorInfo.actionId != 0 -> editorInfo.actionId
            action in actionableEditorActions -> action
            else -> EditorInfo.IME_ACTION_UNSPECIFIED
        }

        if (customLabel.isNotEmpty() && customActionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            enterActionLabel = customLabel
            enterActionVisual = toEnterActionVisual(customActionId)
        } else if (action in actionableEditorActions) {
            enterActionLabel = when (action) {
                EditorInfo.IME_ACTION_GO -> latinIME.getString(R.string.ime_action_go)
                EditorInfo.IME_ACTION_SEARCH -> latinIME.getString(R.string.ime_action_search)
                EditorInfo.IME_ACTION_SEND -> latinIME.getString(R.string.ime_action_send)
                EditorInfo.IME_ACTION_NEXT -> latinIME.getString(R.string.ime_action_next)
                EditorInfo.IME_ACTION_DONE -> latinIME.getString(R.string.ime_action_done)
                EditorInfo.IME_ACTION_PREVIOUS -> latinIME.getString(R.string.ime_action_previous)
                else -> latinIME.getString(R.string.ime_action_enter)
            }
            enterActionVisual = toEnterActionVisual(action)
        }

        voiceKeyboardView?.actionLabel = enterActionLabel
        voiceKeyboardView?.actionVisual = enterActionVisual
    }

    private fun syncView() {
        voiceKeyboardView?.state = currentState
        voiceKeyboardView?.errorMessage = errorMessage
        voiceKeyboardView?.actionLabel = enterActionLabel
        voiceKeyboardView?.actionVisual = enterActionVisual
    }

    private fun scheduleAuthRetry() {
        authRetryJob?.cancel()
        authRetryJob = scope.launch {
            delay(5000)
            if (currentState == VoiceKeyboardView.STATE_ERROR && !micPressed && !micProcessing) {
                Log.d(TAG, "Auto-retrying initialization after auth error")
                modelManager.initializeFirstLocale(false)
            }
        }
    }

    private fun scheduleHoldTimers() {
        cancelHoldTimers()
        uiHandler.postDelayed(holdWarningRunnable, HOLD_WARNING_MS)
        uiHandler.postDelayed(holdAutoStopRunnable, HOLD_AUTO_STOP_MS)
    }

    private fun cancelHoldTimers() {
        uiHandler.removeCallbacks(holdWarningRunnable)
        uiHandler.removeCallbacks(holdAutoStopRunnable)
    }

    private fun startProcessing() {
        if (micProcessing) return
        micProcessing = true
        currentState = VoiceKeyboardView.STATE_PROCESSING
    }

    private fun finishProcessing() {
        micPressed = false
        cancelHoldTimers()
        if (!micProcessing) return
        micProcessing = false
        currentState = VoiceKeyboardView.STATE_READY
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

    private fun toEnterActionVisual(action: Int): VoiceKeyboardView.EnterActionVisual {
        return when (action) {
            EditorInfo.IME_ACTION_GO -> VoiceKeyboardView.EnterActionVisual.GO
            EditorInfo.IME_ACTION_SEARCH -> VoiceKeyboardView.EnterActionVisual.SEARCH
            EditorInfo.IME_ACTION_SEND -> VoiceKeyboardView.EnterActionVisual.SEND
            EditorInfo.IME_ACTION_NEXT -> VoiceKeyboardView.EnterActionVisual.NEXT
            EditorInfo.IME_ACTION_DONE -> VoiceKeyboardView.EnterActionVisual.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> VoiceKeyboardView.EnterActionVisual.PREVIOUS
            else -> VoiceKeyboardView.EnterActionVisual.ENTER
        }
    }

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val HOLD_WARNING_MS = 27_000L
        private const val HOLD_AUTO_STOP_MS = 30_000L
        private val actionableEditorActions = intArrayOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS
        )
    }
}
