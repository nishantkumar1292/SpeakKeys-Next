package helium314.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import com.elishaazaria.sayboard.recognition.logging.Logger
import androidx.core.app.ActivityCompat
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.RecognitionListener
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ModelManager(
    private val context: Context,
    private val listener: Listener,
    private val prefsRepo: PreferencesRepository,
    authTokenProvider: AuthTokenProvider
) {
    private var speechService: MySpeechService? = null
    var isRunning = false
        private set

    val openSettingsOnMic: Boolean
        get() = recognizerSources.size == 0

    private var recognizerSourceProviders = Providers(prefsRepo, authTokenProvider)

    private var recognizerSourceModels: List<InstalledModelReference> = listOf()
    private var recognizerSources: MutableList<RecognizerSource> = ArrayList()
    private var currentRecognizerSourceIndex = 0
    private var currentRecognizerSource: RecognizerSource? = null
    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        reloadModels()
    }

    private fun saveSelectedModel() {
        val source = currentRecognizerSource ?: return
        val model = recognizerSourceModels.getOrNull(currentRecognizerSourceIndex) ?: return
        prefsRepo.setLastSelectedModelPath(model.path)
    }

    private fun restoreSelectedModelIndex(): Int {
        val lastPath = prefsRepo.getLastSelectedModelPath()
        if (lastPath.isEmpty()) return 0
        val index = recognizerSourceModels.indexOfFirst { it.path == lastPath }
        return if (index >= 0) index else 0
    }

    private fun initializeRecognizer(autoStart: Boolean, attributionContext: Context? = null) {
        if (recognizerSources.size == 0) {
            return
        }
        currentRecognizerSource = recognizerSources[currentRecognizerSourceIndex]
        saveSelectedModel()
        listener.onRecognizerSource(currentRecognizerSource!!)

        val source = currentRecognizerSource!!
        scope.launch {
            source.initialize()
            if (autoStart) {
                withContext(Dispatchers.Main) {
                    start(attributionContext)
                }
            }
        }
    }

    val currentRecognizerSourceAddSpaces: Boolean
        get() = currentRecognizerSource?.addSpaces ?: true

    val currentRecognizerSourceIsBatch: Boolean
        get() = currentRecognizerSource?.isBatchRecognizer ?: false

    fun switchToNextRecognizer(autoStart: Boolean, attributionContext: Context? = null) {
        if (recognizerSources.size == 0 || recognizerSources.size == 1) return
        stop(true)
        currentRecognizerSourceIndex++
        if (currentRecognizerSourceIndex >= recognizerSources.size) {
            currentRecognizerSourceIndex = 0
        }
        initializeRecognizer(autoStart, attributionContext)
    }

    fun switchToRecognizerOfLocale(
        locale: SpeakKeysLocale,
        autoStart: Boolean,
        attributionContext: Context? = null
    ): Boolean {
        var bestSource = -1
        var foundLanguage = false
        var foundCountry = false

        recognizerSources.forEachIndexed { index, recognizerSource ->
            if (recognizerSource.locale.language == locale.language) {
                if (recognizerSource.locale.country == locale.country) {
                    if (recognizerSource.locale.variant == locale.variant) {
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                        return@forEachIndexed
                    } else if (!foundCountry) {
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                    }
                } else if (!foundLanguage) {
                    foundLanguage = true
                    bestSource = index
                }
            } else if (recognizerSource.locale == SpeakKeysLocale.ROOT && !foundLanguage && bestSource == -1) {
                bestSource = index
            }
        }

        if (bestSource == -1) {
            return false
        }

        stop(true)
        currentRecognizerSourceIndex = bestSource

        initializeRecognizer(autoStart, attributionContext)

        return true
    }

    fun initializeFirstLocale(autoStart: Boolean, attributionContext: Context? = null): Boolean {
        if (recognizerSources.size == 0) {
            listener.onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            listener.onStateChanged(State.STATE_ERROR)
            return false
        }

        currentRecognizerSourceIndex = restoreSelectedModelIndex()
        initializeRecognizer(autoStart, attributionContext)
        return true
    }

    fun start(attributionContext: Context? = null) {
        if (currentRecognizerSource == null) {
            Logger.w(TAG, "currentRecognizerSource is null!")
            return
        }
        if (currentRecognizerSource!!.closed) {
            Logger.d(TAG, "Recognizer Source is closed, re-initializing: ${currentRecognizerSource!!.name}")
            initializeRecognizer(true, attributionContext)
            return
        }
        if (isRunning || speechService != null) {
            speechService!!.stop()
        }
        isRunning = true
        listener.onStateChanged(State.STATE_LISTENING)
        try {
            val recognizer = currentRecognizerSource!!.recognizer
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            speechService = MySpeechService(recognizer, recognizer.sampleRate, attributionContext)
            speechService!!.recordDevice = recordDevice
            speechService!!.startListening(listener)
        } catch (e: IOException) {
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    fun reloadModels() {
        val currentModels = prefsRepo.getModelsOrder().toMutableList()
        val installedModels = recognizerSourceProviders.installedModels()
        currentModels.removeAll { it !in installedModels }
        for (model in installedModels) {
            if (model !in currentModels) {
                currentModels.add(model)
            }
        }
        prefsRepo.setModelsOrder(currentModels)

        val newModels = prefsRepo.getModelsOrder()
        if (newModels == recognizerSourceModels)
            return

        recognizerSources.clear()
        recognizerSourceModels = newModels
        recognizerSourceModels.forEach { model ->
            recognizerSourceProviders.recognizerSourceForModel(model)?.let {
                recognizerSources.add(it)
            }
        }

        if (recognizerSources.size == 0) {
            listener.onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    fun pause(checked: Boolean) {
        if (speechService != null) {
            speechService!!.setPause(checked)
            if (checked) {
                listener.onStateChanged(State.STATE_PAUSED)
            } else {
                listener.onStateChanged(State.STATE_LISTENING)
            }
        }
    }

    val isPaused: Boolean
        get() = speechService != null

    fun stop(forceFreeRam: Boolean = false) {
        speechService?.let {
            executor.execute {
                it.stop()
                it.shutdown()
            }
        }
        speechService = null
        isRunning = false
        stopRecognizerSource(forceFreeRam)
    }

    private fun stopRecognizerSource(freeRam: Boolean) {
        currentRecognizerSource?.let {
            executor.execute {
                it.close(freeRam)
            }
        }
        listener.onStateChanged(State.STATE_STOPPED)
    }

    fun onDestroy() {
        stop(true)
    }

    var recordDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            speechService?.recordDevice = value
        }

    companion object {
        private const val TAG = "ModelManager"
    }

    interface Listener : RecognitionListener {
        fun onStateChanged(state: State)
        fun onError(type: ErrorType)
        fun onRecognizerSource(source: RecognizerSource)
    }

    enum class State {
        STATE_INITIAL, STATE_LOADING, STATE_READY, STATE_LISTENING, STATE_PAUSED, STATE_ERROR, STATE_STOPPED
    }

    enum class ErrorType {
        MIC_IN_USE, NO_RECOGNIZERS_INSTALLED
    }
}
