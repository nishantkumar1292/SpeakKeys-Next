// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread

data class AndroidOnDeviceRecognitionConfig(
    val primaryLanguageTag: String = "hi-IN",
    val allowedLanguageTags: List<String> = listOf("hi-IN", "en-IN"),
    val enableLanguageSwitch: Boolean = true,
    val maximumResults: Int = 3,
) {
    init {
        require(primaryLanguageTag.isNotBlank()) { "A primary language is required" }
        require(allowedLanguageTags.isNotEmpty()) { "At least one allowed language is required" }
        require(allowedLanguageTags.none(String::isBlank)) { "Language tags cannot be blank" }
        require(maximumResults in 1..10) { "maximumResults must be between 1 and 10" }
    }

    @SuppressLint("InlinedApi")
    internal fun toIntent(sdkInt: Int = Build.VERSION.SDK_INT): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maximumResults)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                enableLanguageSwitch && allowedLanguageTags.distinct().size > 1
            ) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
                )
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    ArrayList(allowedLanguageTags.distinct()),
                )
            }
        }
}

enum class AndroidOnDeviceRecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    UNAVAILABLE,
    DESTROYED,
}

enum class AndroidOnDeviceErrorCategory {
    PERMISSION,
    AUDIO,
    NO_SPEECH,
    NO_MATCH,
    BUSY,
    LANGUAGE,
    SERVICE,
    RATE_LIMIT,
    UNKNOWN,
}

data class AndroidOnDeviceRecognitionError(
    val category: AndroidOnDeviceErrorCategory,
    val platformCode: Int,
)

data class AndroidRecognitionHypothesis(
    val text: String,
    val alternatives: List<String>,
    val confidenceScores: List<Float>,
)

data class AndroidDetectedLanguage(
    val languageTag: String?,
    val confidenceLevel: Int?,
    val localeAlternatives: List<String>,
)

interface AndroidOnDeviceRecognitionCallback {
    fun onStateChanged(state: AndroidOnDeviceRecognitionState) = Unit
    fun onReady() = Unit
    fun onSpeechStarted() = Unit
    fun onSpeechEnded() = Unit
    fun onRmsChanged(rmsDb: Float) = Unit
    fun onPartialResult(result: AndroidRecognitionHypothesis) = Unit
    fun onFinalResult(result: AndroidRecognitionHypothesis) = Unit
    fun onLanguageDetected(language: AndroidDetectedLanguage) = Unit
    fun onError(error: AndroidOnDeviceRecognitionError) = Unit
}

internal interface PlatformSpeechRecognizer {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

private class AndroidPlatformSpeechRecognizer(
    private val delegate: SpeechRecognizer,
) : PlatformSpeechRecognizer {
    override fun setRecognitionListener(listener: RecognitionListener) =
        delegate.setRecognitionListener(listener)

    override fun startListening(intent: Intent) = delegate.startListening(intent)
    override fun stopListening() = delegate.stopListening()
    override fun cancel() = delegate.cancel()
    override fun destroy() = delegate.destroy()
}

internal fun interface PlatformSpeechRecognizerFactory {
    fun create(context: Context): PlatformSpeechRecognizer
}

/**
 * Controller for Android's API 31+ on-device recognizer.
 *
 * This intentionally does not implement SpeakKeys' PCM [com.elishaazaria.sayboard.recognition.recognizers.Recognizer]:
 * Android owns the microphone for this engine. Integration should route ENGINE_MIC sessions here
 * instead of constructing MySpeechService.
 */
class AndroidOnDeviceRecognizerController internal constructor(
    context: Context,
    private val callback: AndroidOnDeviceRecognitionCallback,
    private val availability: () -> Boolean,
    private val factory: PlatformSpeechRecognizerFactory,
    watchdogScheduler: WatchdogScheduler = MainThreadWatchdogScheduler(),
) : AutoCloseable {
    constructor(
        context: Context,
        callback: AndroidOnDeviceRecognitionCallback,
    ) : this(
        context = context,
        callback = callback,
        availability = { isAvailable(context) },
        factory = PlatformSpeechRecognizerFactory { recognitionContext ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AndroidPlatformSpeechRecognizer(
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(recognitionContext),
                )
            } else {
                throw UnsupportedOperationException("On-device speech requires Android 12")
            }
        },
    )

    private val context = context.applicationContext
    private var recognizer: PlatformSpeechRecognizer? = null
    private var generation = 0L
    private var finalizationGeneration: Long? = null
    private val finalizationWatchdog = ResettableWatchdog(
        watchdogScheduler,
        FINAL_RESULT_TIMEOUT_MILLIS,
        ::onFinalizationTimeout,
    )
    private var state = if (availability()) {
        AndroidOnDeviceRecognitionState.IDLE
    } else {
        AndroidOnDeviceRecognitionState.UNAVAILABLE
    }

    val audioOwnership: AudioOwnership = AudioOwnership.ENGINE_MIC

    val currentState: AndroidOnDeviceRecognitionState get() = state

    /** Returns false when unavailable, destroyed, or a previous utterance is still active. */
    @MainThread
    fun start(config: AndroidOnDeviceRecognitionConfig = AndroidOnDeviceRecognitionConfig()): Boolean {
        checkMainThread()
        if (state == AndroidOnDeviceRecognitionState.DESTROYED ||
            state == AndroidOnDeviceRecognitionState.LISTENING ||
            state == AndroidOnDeviceRecognitionState.PROCESSING
        ) {
            return false
        }
        if (!availability()) {
            transitionTo(AndroidOnDeviceRecognitionState.UNAVAILABLE)
            callback.onError(
                AndroidOnDeviceRecognitionError(
                    AndroidOnDeviceErrorCategory.SERVICE,
                    ERROR_ON_DEVICE_UNAVAILABLE,
                ),
            )
            return false
        }

        cancelFinalizationWatchdog()
        val sessionGeneration = ++generation
        return try {
            val activeRecognizer = recognizer ?: factory.create(context).also { recognizer = it }
            activeRecognizer.setRecognitionListener(SessionListener(sessionGeneration))
            activeRecognizer.startListening(config.toIntent())
            transitionTo(AndroidOnDeviceRecognitionState.LISTENING)
            true
        } catch (_: SecurityException) {
            releaseRecognizer()
            transitionTo(AndroidOnDeviceRecognitionState.IDLE)
            callback.onError(
                AndroidOnDeviceRecognitionError(
                    AndroidOnDeviceErrorCategory.PERMISSION,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                ),
            )
            false
        } catch (_: RuntimeException) {
            releaseRecognizer()
            transitionTo(AndroidOnDeviceRecognitionState.IDLE)
            callback.onError(
                AndroidOnDeviceRecognitionError(
                    AndroidOnDeviceErrorCategory.SERVICE,
                    SpeechRecognizer.ERROR_CLIENT,
                ),
            )
            false
        }
    }

    /** Requests a final result for audio captured so far. */
    @MainThread
    fun stop(): Boolean {
        checkMainThread()
        if (state != AndroidOnDeviceRecognitionState.LISTENING) return false
        val sessionGeneration = generation
        return try {
            recognizer?.stopListening()
            // Some recognizers synchronously deliver onResults/onError/onEndOfSpeech from stop().
            // Do not overwrite a terminal callback's IDLE state or duplicate an armed watchdog.
            if (generation == sessionGeneration && state == AndroidOnDeviceRecognitionState.LISTENING) {
                transitionTo(AndroidOnDeviceRecognitionState.PROCESSING)
                armFinalizationWatchdog(sessionGeneration)
            }
            true
        } catch (_: RuntimeException) {
            cancelFinalizationWatchdog()
            releaseRecognizer()
            transitionTo(AndroidOnDeviceRecognitionState.IDLE)
            callback.onError(
                AndroidOnDeviceRecognitionError(
                    AndroidOnDeviceErrorCategory.SERVICE,
                    SpeechRecognizer.ERROR_CLIENT,
                ),
            )
            false
        }
    }

    /** Cancels without committing a result and invalidates all callbacks from that session. */
    @MainThread
    fun cancel() {
        checkMainThread()
        if (state == AndroidOnDeviceRecognitionState.DESTROYED) return
        cancelFinalizationWatchdog()
        generation++
        releaseRecognizer(cancelFirst = true)
        transitionTo(
            if (availability()) AndroidOnDeviceRecognitionState.IDLE
            else AndroidOnDeviceRecognitionState.UNAVAILABLE,
        )
    }

    @MainThread
    override fun close() {
        checkMainThread()
        if (state == AndroidOnDeviceRecognitionState.DESTROYED) return
        cancelFinalizationWatchdog()
        generation++
        releaseRecognizer(cancelFirst = true)
        transitionTo(AndroidOnDeviceRecognitionState.DESTROYED)
    }

    private fun releaseRecognizer(cancelFirst: Boolean = false) {
        val active = recognizer ?: return
        recognizer = null
        if (cancelFirst) runCatching(active::cancel)
        runCatching(active::destroy)
    }

    private fun transitionTo(newState: AndroidOnDeviceRecognitionState) {
        if (state != newState) {
            state = newState
            callback.onStateChanged(newState)
        }
    }

    private fun armFinalizationWatchdog(sessionGeneration: Long) {
        if (sessionGeneration != generation || state != AndroidOnDeviceRecognitionState.PROCESSING) {
            return
        }
        finalizationGeneration = sessionGeneration
        finalizationWatchdog.refresh()
    }

    private fun cancelFinalizationWatchdog() {
        finalizationGeneration = null
        finalizationWatchdog.cancel()
    }

    private fun onFinalizationTimeout() {
        val timedOutGeneration = finalizationGeneration ?: return
        if (timedOutGeneration != generation || state != AndroidOnDeviceRecognitionState.PROCESSING) {
            return
        }
        finalizationGeneration = null
        generation++
        releaseRecognizer(cancelFirst = true)
        transitionTo(AndroidOnDeviceRecognitionState.IDLE)
        callback.onError(
            AndroidOnDeviceRecognitionError(
                AndroidOnDeviceErrorCategory.SERVICE,
                ERROR_FINAL_RESULT_TIMEOUT,
            ),
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Android speech recognition must be controlled from the main thread"
        }
    }

    private inner class SessionListener(
        private val sessionGeneration: Long,
    ) : RecognitionListener {
        private fun isCurrent(): Boolean =
            sessionGeneration == generation && state != AndroidOnDeviceRecognitionState.DESTROYED

        override fun onReadyForSpeech(params: Bundle?) {
            if (isCurrent()) callback.onReady()
        }

        override fun onBeginningOfSpeech() {
            if (isCurrent()) callback.onSpeechStarted()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isCurrent()) callback.onRmsChanged(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrent()) return
            transitionTo(AndroidOnDeviceRecognitionState.PROCESSING)
            armFinalizationWatchdog(sessionGeneration)
            callback.onSpeechEnded()
        }

        override fun onError(error: Int) {
            if (!isCurrent()) return
            cancelFinalizationWatchdog()
            generation++
            transitionTo(AndroidOnDeviceRecognitionState.IDLE)
            callback.onError(AndroidOnDeviceRecognitionError(mapError(error), error))
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            val hypothesis = results.toHypothesis()
            cancelFinalizationWatchdog()
            generation++
            transitionTo(AndroidOnDeviceRecognitionState.IDLE)
            callback.onFinalResult(hypothesis)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent()) return
            callback.onPartialResult(partialResults.toHypothesis())
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onLanguageDetection(results: Bundle) {
            if (!isCurrent() || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            callback.onLanguageDetected(
                AndroidDetectedLanguage(
                    languageTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE),
                    confidenceLevel = if (
                        results.containsKey(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
                    ) {
                        results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
                    } else {
                        null
                    },
                    localeAlternatives = results.getStringArrayList(
                        SpeechRecognizer.TOP_LOCALE_ALTERNATIVES,
                    ).orEmpty(),
                ),
            )
        }
    }

    private fun Bundle?.toHypothesis(): AndroidRecognitionHypothesis {
        val alternatives = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidenceScores = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.toList()
            .orEmpty()
        return AndroidRecognitionHypothesis(
            text = alternatives.firstOrNull().orEmpty().trim(),
            alternatives = alternatives,
            confidenceScores = confidenceScores,
        )
    }

    private fun mapError(error: Int): AndroidOnDeviceErrorCategory = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AndroidOnDeviceErrorCategory.PERMISSION
        SpeechRecognizer.ERROR_AUDIO -> AndroidOnDeviceErrorCategory.AUDIO
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> AndroidOnDeviceErrorCategory.NO_SPEECH
        SpeechRecognizer.ERROR_NO_MATCH -> AndroidOnDeviceErrorCategory.NO_MATCH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> AndroidOnDeviceErrorCategory.BUSY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> AndroidOnDeviceErrorCategory.LANGUAGE
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> AndroidOnDeviceErrorCategory.RATE_LIMIT
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_CLIENT,
        -> AndroidOnDeviceErrorCategory.SERVICE
        else -> AndroidOnDeviceErrorCategory.UNKNOWN
    }

    companion object {
        /** App-private sentinel; platform recognition errors are non-negative constants. */
        const val ERROR_ON_DEVICE_UNAVAILABLE = -1
        const val ERROR_FINAL_RESULT_TIMEOUT = -2

        private const val FINAL_RESULT_TIMEOUT_MILLIS = 4_000L

        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
