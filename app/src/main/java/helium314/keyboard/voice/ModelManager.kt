package helium314.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import com.elishaazaria.sayboard.recognition.logging.Logger
import androidx.core.app.ActivityCompat
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.RecognitionListener
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import helium314.keyboard.voice.local.LocalSpeechModelCatalog
import helium314.keyboard.voice.local.LocalSpeechRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DeferredRecognizerStart(
    val source: RecognizerSource,
    val attributionContext: Context?,
)

internal class RecognizerInitializationToken internal constructor(
    val generation: Long,
    val source: RecognizerSource,
    internal var autoStartRequested: Boolean,
    internal var attributionContext: Context?,
)

internal data class RecognizerInitializationCompletion(
    val valid: Boolean,
    val deferredStart: DeferredRecognizerStart?,
)

/**
 * Coalesces initialization before the source has had a chance to publish LOADING.
 *
 * The token is installed synchronously on the caller thread. A mic press can therefore upgrade an
 * already queued or running prewarm without invalidating its native model load.
 */
internal class RecognizerInitializationTracker {
    private val lock = Any()
    private var generation = 0L
    private var pending: RecognizerInitializationToken? = null

    /** Returns a token only when the caller must launch a new initialization coroutine. */
    fun request(
        source: RecognizerSource,
        autoStart: Boolean,
        attributionContext: Context?,
    ): RecognizerInitializationToken? = synchronized(lock) {
        pending?.takeIf { it.source === source }?.let { existing ->
            if (autoStart) {
                existing.autoStartRequested = true
                if (attributionContext != null) existing.attributionContext = attributionContext
            }
            return@synchronized null
        }
        RecognizerInitializationToken(
            generation = ++generation,
            source = source,
            autoStartRequested = autoStart,
            attributionContext = attributionContext,
        ).also { pending = it }
    }

    fun complete(token: RecognizerInitializationToken): RecognizerInitializationCompletion =
        synchronized(lock) {
            if (pending !== token || token.generation != generation) {
                return@synchronized RecognizerInitializationCompletion(false, null)
            }
            pending = null
            RecognizerInitializationCompletion(
                valid = true,
                deferredStart = if (token.autoStartRequested) {
                    DeferredRecognizerStart(token.source, token.attributionContext)
                } else {
                    null
                },
            )
        }

    fun isCurrent(token: RecognizerInitializationToken): Boolean = synchronized(lock) {
        token.generation == generation && pending === token
    }

    fun isGenerationCurrent(token: RecognizerInitializationToken): Boolean = synchronized(lock) {
        token.generation == generation
    }

    fun hasPendingFor(source: RecognizerSource): Boolean = synchronized(lock) {
        pending?.source === source
    }

    fun invalidate() = synchronized(lock) {
        generation++
        pending = null
    }
}

/** Prevents a replacement AudioRecord from starting until the prior one is stopped and released. */
internal class CaptureCleanupStartGate {
    private val lock = Any()
    private var pendingCleanups = 0
    private var deferredStart: DeferredRecognizerStart? = null

    fun cleanupScheduled() = synchronized(lock) { pendingCleanups++ }

    /** Returns true when [start] was retained for delivery after cleanup. */
    fun deferIfNeeded(start: DeferredRecognizerStart): Boolean = synchronized(lock) {
        if (pendingCleanups == 0) return@synchronized false
        deferredStart = start
        true
    }

    fun cleanupFinished(): DeferredRecognizerStart? = synchronized(lock) {
        check(pendingCleanups > 0) { "Capture cleanup finished without a matching start" }
        pendingCleanups--
        if (pendingCleanups == 0) deferredStart.also { deferredStart = null } else null
    }

    fun cancelDeferredStart() = synchronized(lock) {
        deferredStart = null
    }
}

internal fun canReuseRecognizerSourceAcrossReload(model: InstalledModelReference): Boolean =
    LocalSpeechModelCatalog.find(model.path)?.runtime == LocalSpeechRuntime.VOSK

internal data class RecognizerCatalogReconciliation(
    val sessionModels: List<InstalledModelReference>,
    /** Null while Direct Boot or a vault failure makes provider discovery incomplete. */
    val modelsToPersist: List<InstalledModelReference>?,
)

internal fun reconcileRecognizerCatalog(
    currentModels: List<InstalledModelReference>,
    installedModels: List<InstalledModelReference>,
    authoritative: Boolean,
): RecognizerCatalogReconciliation {
    val installedByPath = installedModels.associateBy(InstalledModelReference::path)
    val reconciled = currentModels
        .mapNotNull { installedByPath[it.path] }
        .toMutableList()
    installedModels.forEach { model ->
        if (reconciled.none { it.path == model.path }) reconciled += model
    }
    return RecognizerCatalogReconciliation(
        sessionModels = reconciled,
        modelsToPersist = reconciled.takeIf { authoritative },
    )
}

internal fun persistProviderCatalogIfAuthoritative(
    authoritative: Boolean,
    persist: () -> Unit,
): Boolean {
    if (!authoritative) return false
    persist()
    return true
}

class ModelManager(
    private val context: Context,
    private val listener: Listener,
    private val prefsRepo: PreferencesRepository,
    authTokenProvider: AuthTokenProvider,
    /** Allows Direct Boot callers to defer persistence until provider discovery is authoritative. */
    private val canPersistProviderCatalog: () -> Boolean = { true },
) {
    private sealed interface CaptureSession {
        fun requestFinish(): Boolean
        fun requestCancel(): Boolean
        fun awaitStopped()
        fun shutdown()
        fun setRecordDevice(device: AudioDeviceInfo?) = Unit
    }

    private class AppPcmCaptureSession(
        private val service: MySpeechService,
    ) : CaptureSession {
        fun startListening(listener: RecognitionListener): Boolean = service.startListening(listener)
        fun setPause(paused: Boolean) = service.setPause(paused)
        override fun requestFinish(): Boolean = service.requestFinish()
        override fun requestCancel(): Boolean = service.requestCancel()
        override fun awaitStopped() {
            service.awaitStopped()
        }
        override fun shutdown() = service.shutdown()
        override fun setRecordDevice(device: AudioDeviceInfo?) {
            service.recordDevice = device
        }
    }

    private class EngineMicCaptureSession(
        private val source: EngineMicRecognizerSource,
    ) : CaptureSession {
        override fun requestFinish(): Boolean = source.stopListening()
        override fun requestCancel(): Boolean {
            source.cancelListening()
            return true
        }
        override fun awaitStopped() = Unit
        override fun shutdown() = Unit
    }

    private data class ActiveSession(
        val id: Long,
        val source: RecognizerSource,
        val capture: CaptureSession,
        val cleanupScheduled: AtomicBoolean = AtomicBoolean(false)
    )

    private val sessionLock = Any()
    private val initializationLock = Any()
    private val initializationMutex = Mutex()
    private val initializationTracker = RecognizerInitializationTracker()
    private val captureCleanupStartGate = CaptureCleanupStartGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession: ActiveSession? = null
    private var nextSessionId = 0L
    private val closingSources = mutableSetOf<RecognizerSource>()
    private var startAfterSourceClose: DeferredRecognizerStart? = null

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var destroyed = false

    val openSettingsOnMic: Boolean
        get() = recognizerSources.size == 0

    private var recognizerSourceProviders = AndroidRecognizerProviders(
        context,
        prefsRepo,
        authTokenProvider,
        canPersistProviderCatalog,
    )

    private var recognizerSourceModels: List<InstalledModelReference> = listOf()
    private var recognizerSources: MutableList<RecognizerSource> = ArrayList()
    private var currentRecognizerSourceIndex = 0
    @Volatile
    private var currentRecognizerSource: RecognizerSource? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        reloadModels()
    }

    private fun saveSelectedModel() {
        val source = currentRecognizerSource ?: return
        val model = recognizerSourceModels.getOrNull(currentRecognizerSourceIndex) ?: return
        persistProviderCatalogIfAuthoritative(canPersistProviderCatalog()) {
            prefsRepo.setLastSelectedModelPath(model.path)
        }
    }

    private fun restoreSelectedModelIndex(): Int {
        val lastPath = prefsRepo.getLastSelectedModelPath()
        if (lastPath.isEmpty()) return 0
        val index = recognizerSourceModels.indexOfFirst { it.path == lastPath }
        return if (index >= 0) index else 0
    }

    private fun initializeRecognizer(autoStart: Boolean, attributionContext: Context? = null) {
        if (destroyed || recognizerSources.size == 0) {
            return
        }
        currentRecognizerSource = recognizerSources[currentRecognizerSourceIndex]
        saveSelectedModel()
        listener.onRecognizerSource(currentRecognizerSource!!)

        val source = currentRecognizerSource!!
        val token = initializationTracker.request(source, autoStart, attributionContext) ?: return
        scope.launch {
            var initializedByThisJob = false
            initializationMutex.withLock {
                if (!isInitializationCurrent(token)) return@withLock
                try {
                    source.initialize()
                    if (!isInitializationCurrent(token)) {
                        closeStaleInitializationUnlessSuperseded(source)
                        return@withLock
                    }
                    initializedByThisJob = true
                } catch (error: Exception) {
                    if (isInitializationCurrent(token)) {
                        initializationTracker.complete(token)
                        withContext(Dispatchers.Main) {
                            listener.onError(error)
                            listener.onStateChanged(State.STATE_ERROR)
                        }
                    } else {
                        closeStaleInitializationUnlessSuperseded(source)
                    }
                    return@launch
                }
            }
            if (!initializedByThisJob) return@launch
            val completion = initializationTracker.complete(token)
            if (!completion.valid || !isSourceCurrent(source)) {
                closeStaleInitializationUnlessSuperseded(source)
                return@launch
            }
            completion.deferredStart?.let { deferredStart ->
                withContext(Dispatchers.Main) {
                    if (!initializationTracker.isGenerationCurrent(token) ||
                        !isSourceCurrent(source) || destroyed
                    ) {
                        return@withContext
                    }
                    val initializationFailure = recognizerInitializationFailure(source)
                    if (initializationFailure != null) {
                        // A mic press can arrive while a heavyweight local source is still loading.
                        // Sources report validation/auth failures through state rather than throwing,
                        // so turn that terminal state into the same callback path as a failed start.
                        listener.onError(initializationFailure)
                        listener.onStateChanged(State.STATE_ERROR)
                    } else {
                        start(deferredStart.attributionContext)
                    }
                }
            }
        }
    }

    private fun isInitializationCurrent(token: RecognizerInitializationToken): Boolean =
        !destroyed && token.source === currentRecognizerSource && initializationTracker.isCurrent(token)

    private fun invalidateInitialization() {
        initializationTracker.invalidate()
    }

    private fun isSourceCurrent(source: RecognizerSource): Boolean =
        !destroyed && source === currentRecognizerSource

    private fun closeStaleInitializationUnlessSuperseded(source: RecognizerSource) {
        if (!initializationTracker.hasPendingFor(source)) {
            closeRecognizerSource(freeRam = true, source = source)
        }
    }

    val currentRecognizerSourceAddSpaces: Boolean
        get() = currentRecognizerSource?.addSpaces ?: true

    val currentRecognizerSourceIsBatch: Boolean
        get() = currentRecognizerSource?.isBatchRecognizer ?: false

    fun switchToNextRecognizer(autoStart: Boolean, attributionContext: Context? = null) {
        if (recognizerSources.size == 0 || recognizerSources.size == 1) return
        cancel(true)
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

        cancel(true)
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
        if (destroyed) return
        val source = currentRecognizerSource
        if (source == null) {
            Logger.w(TAG, "currentRecognizerSource is null!")
            return
        }
        if (deferStartUntilSourceClosed(source, attributionContext)) return
        if (initializationTracker.hasPendingFor(source)) {
            initializeRecognizer(true, attributionContext)
            return
        }
        if (source.closed) {
            Logger.d(TAG, "Recognizer Source is closed, re-initializing: ${source.name}")
            initializeRecognizer(true, attributionContext)
            return
        }
        if (synchronized(sessionLock) { activeSession != null }) {
            Logger.w(TAG, "Ignoring start while the previous recognizer session is still active")
            return
        }
        if (captureCleanupStartGate.deferIfNeeded(DeferredRecognizerStart(source, attributionContext))) {
            Logger.d(TAG, "Deferring start until the previous AudioRecord is released")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            isRunning = false
            return
        }

        var createdSession: ActiveSession? = null
        try {
            val capture: CaptureSession = if (source is EngineMicRecognizerSource) {
                EngineMicCaptureSession(source)
            } else {
                val recognizer = source.recognizer
                AppPcmCaptureSession(
                    MySpeechService(recognizer, recognizer.sampleRate, attributionContext),
                ).also { it.setRecordDevice(recordDevice) }
            }
            val session = synchronized(sessionLock) {
                ActiveSession(++nextSessionId, source, capture).also { activeSession = it }
            }
            createdSession = session
            val started = when (capture) {
                is AppPcmCaptureSession -> capture.startListening(listenerForSession(session.id))
                is EngineMicCaptureSession -> source
                    .let { it as EngineMicRecognizerSource }
                    .startListening(listenerForSession(session.id))
            }
            if (!started) {
                val stillActive = synchronized(sessionLock) {
                    if (activeSession === session) {
                        activeSession = null
                        true
                    } else {
                        false
                    }
                }
                // Engine-owned recognizers may report their precise error synchronously.
                if (!stillActive) return
                capture.requestCancel()
                capture.awaitStopped()
                capture.shutdown()
                listener.onError(IOException("Failed to start recognizer thread"))
                listener.onStateChanged(State.STATE_ERROR)
                return
            }
            // A platform recognizer is allowed to report a terminal callback from startListening().
            // Do not resurrect a session that its callback already completed synchronously.
            if (!isCurrentSession(session.id)) return
            isRunning = true
            listener.onStateChanged(State.STATE_LISTENING)
        } catch (e: IOException) {
            cleanupFailedStart(createdSession)
            isRunning = false
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
        } catch (e: Exception) {
            cleanupFailedStart(createdSession)
            isRunning = false
            listener.onError(e)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    private fun cleanupFailedStart(session: ActiveSession?) {
        if (session != null) {
            synchronized(sessionLock) {
                if (activeSession === session) activeSession = null
            }
            session.capture.requestCancel()
            scheduleSessionCleanup(session)
        }
    }

    private fun deferStartUntilSourceClosed(
        source: RecognizerSource,
        attributionContext: Context?,
    ): Boolean = synchronized(initializationLock) {
        if (source !in closingSources) return@synchronized false
        startAfterSourceClose = DeferredRecognizerStart(source, attributionContext)
        true
    }

    private fun listenerForSession(sessionId: Long) = object : RecognitionListener {
        override fun onResult(text: String?) {
            if (isCurrentSession(sessionId)) listener.onResult(text)
        }

        override fun onFinalResult(text: String?) {
            completeCurrentSession(sessionId) { listener.onFinalResult(text) }
        }

        override fun onPartialResult(partialText: String?) {
            if (isCurrentSession(sessionId)) listener.onPartialResult(partialText)
        }

        override fun onError(e: Exception?) {
            completeCurrentSession(sessionId) {
                listener.onError(e)
                listener.onStateChanged(State.STATE_ERROR)
            }
        }

        override fun onTimeout() {
            completeCurrentSession(sessionId) { listener.onTimeout() }
        }
    }

    private fun isCurrentSession(sessionId: Long): Boolean = synchronized(sessionLock) {
        activeSession?.id == sessionId
    }

    private inline fun completeCurrentSession(sessionId: Long, callback: () -> Unit) {
        val completedSession = synchronized(sessionLock) {
            if (activeSession?.id != sessionId) {
                null
            } else {
                activeSession.also { activeSession = null }
            }.also {
                if (it != null) isRunning = false
            }
        }
        if (completedSession != null) {
            scheduleSessionCleanup(completedSession)
            callback()
            closeRecognizerSource(freeRam = false, source = completedSession.source)
        }
    }

    private fun removeCurrentSession(): ActiveSession? = synchronized(sessionLock) {
        activeSession.also {
            activeSession = null
            if (it != null) {
                isRunning = false
            }
        }
    }

    fun reloadModels() {
        if (destroyed || synchronized(sessionLock) { activeSession != null }) return
        invalidateInitialization()
        val currentModels = prefsRepo.getModelsOrder()
        val installedModels = recognizerSourceProviders.installedModels()
        val catalog = reconcileRecognizerCatalog(
            currentModels = currentModels,
            installedModels = installedModels,
            authoritative = canPersistProviderCatalog(),
        )
        // Model names and enum metadata can evolve between releases. Paths are the stable identity,
        // so refresh metadata without silently moving the user's preferred model to the end.
        catalog.modelsToPersist?.let(prefsRepo::setModelsOrder)

        val newModels = catalog.sessionModels
        val oldSourcesByPath = recognizerSourceModels.zip(recognizerSources).associate { it.first.path to it.second }
        // Cloud and OEM sources are recreated so credentials, auth, output mode, and language-pack
        // state take effect. A verified Vosk source has no mutable configuration; retaining it avoids
        // unloading and reloading hundreds of MB every time Android switches editor fields.
        val resolved = newModels.mapNotNull { model ->
            val reusable = oldSourcesByPath[model.path]?.takeIf {
                canReuseRecognizerSourceAcrossReload(model)
            }
            (reusable ?: recognizerSourceProviders.recognizerSourceForModel(model))?.let { model to it }
        }
        val retainedSources = resolved.map { it.second }
        recognizerSources.forEach { oldSource ->
            if (retainedSources.none { it === oldSource }) {
                closeRecognizerSource(freeRam = true, source = oldSource)
            }
        }
        currentRecognizerSource = null
        recognizerSourceModels = resolved.map { it.first }
        recognizerSources = resolved.map { it.second }.toMutableList()
        currentRecognizerSourceIndex = restoreSelectedModelIndex()

        if (recognizerSources.size == 0) {
            listener.onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    fun pause(checked: Boolean) {
        val capture = synchronized(sessionLock) { activeSession?.capture }
        if (capture is AppPcmCaptureSession && isRunning) {
            capture.setPause(checked)
            if (checked) {
                listener.onStateChanged(State.STATE_PAUSED)
            } else {
                listener.onStateChanged(State.STATE_LISTENING)
            }
        }
    }

    val isPaused: Boolean
        get() = synchronized(sessionLock) { activeSession != null } && isRunning

    /**
     * Finalizes the current utterance. Kept as the backwards-compatible public stop API.
     * Call [cancel] when audio must be discarded without invoking provider transcription.
     */
    fun stop(forceFreeRam: Boolean = false) {
        val session = synchronized(sessionLock) { activeSession }
        isRunning = false
        session?.let {
            it.capture.requestFinish()
            if (it.capture is AppPcmCaptureSession) scheduleSessionCleanup(it)
        }
        listener.onStateChanged(State.STATE_STOPPED)
        if (forceFreeRam && session == null) stopRecognizerSource(true)
    }

    /** Discards the current utterance without calling Recognizer.getFinalResult(). */
    fun cancel(forceFreeRam: Boolean = false) {
        invalidateInitialization()
        captureCleanupStartGate.cancelDeferredStart()
        synchronized(initializationLock) { startAfterSourceClose = null }
        val session = removeCurrentSession()
        isRunning = false
        session?.let {
            it.capture.requestCancel()
            scheduleSessionCleanup(it)
        }
        stopRecognizerSource(
            freeRam = forceFreeRam,
            source = session?.source ?: currentRecognizerSource.takeIf { forceFreeRam },
        )
    }

    private fun scheduleSessionCleanup(session: ActiveSession) {
        if (!session.cleanupScheduled.compareAndSet(false, true)) return
        val blocksNextCapture = session.capture is AppPcmCaptureSession
        if (blocksNextCapture) captureCleanupStartGate.cleanupScheduled()
        executor.execute {
            try {
                session.capture.awaitStopped()
                session.capture.shutdown()
            } finally {
                if (blocksNextCapture) {
                    mainHandler.post {
                        val deferredStart = captureCleanupStartGate.cleanupFinished()
                        if (deferredStart != null &&
                            !destroyed && deferredStart.source === currentRecognizerSource &&
                            synchronized(sessionLock) { activeSession == null }
                        ) {
                            start(deferredStart.attributionContext)
                        }
                    }
                }
            }
        }
    }

    private fun closeRecognizerSource(
        freeRam: Boolean,
        source: RecognizerSource?,
    ) {
        source ?: return
        if (freeRam) {
            val newlyClosing = synchronized(initializationLock) { closingSources.add(source) }
            if (!newlyClosing) return
        }
        val closeAction = Runnable {
            try {
                source.close(freeRam)
            } finally {
                if (freeRam) mainHandler.post { onSourceCloseFinished(source) }
            }
        }
        try {
            executor.execute(closeAction)
        } catch (_: RejectedExecutionException) {
            // A stale initialization may finish after onDestroy shut the executor down.
            closeAction.run()
        }
    }

    private fun onSourceCloseFinished(source: RecognizerSource) {
        val deferredStart = synchronized(initializationLock) {
            closingSources.remove(source)
            startAfterSourceClose
                ?.takeIf { it.source === source }
                ?.also { startAfterSourceClose = null }
        }
        if (deferredStart != null && !destroyed && source === currentRecognizerSource) {
            start(deferredStart.attributionContext)
        }
    }

    private fun stopRecognizerSource(
        freeRam: Boolean,
        source: RecognizerSource? = currentRecognizerSource,
    ) {
        closeRecognizerSource(freeRam, source)
        listener.onStateChanged(State.STATE_STOPPED)
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        invalidateInitialization()
        cancel(true)
        scope.cancel()
        executor.shutdown()
    }

    var recordDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            synchronized(sessionLock) { activeSession?.capture }?.setRecordDevice(value)
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

internal class RecognizerInitializationException(message: String) : IllegalStateException(message)

internal fun recognizerInitializationFailure(source: RecognizerSource): Exception? {
    if (!source.closed && source.stateFlow.value != RecognizerState.ERROR) return null
    return RecognizerInitializationException(
        source.errorMessage.takeIf(String::isNotBlank) ?: "Speech recognition is unavailable",
    )
}
