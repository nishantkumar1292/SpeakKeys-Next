// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Availability of one exact, normalized BCP-47 language tag in an on-device recognizer. */
enum class AndroidOnDeviceLanguageAvailability {
    INSTALLED,
    PENDING,
    DOWNLOADABLE,
    UNSUPPORTED,
}

data class AndroidOnDeviceLanguageStatus(
    /** Canonical BCP-47 form, for example `hi-IN`. */
    val languageTag: String,
    val availability: AndroidOnDeviceLanguageAvailability,
)

data class AndroidOnDeviceLanguageSupport(
    val languages: List<AndroidOnDeviceLanguageStatus>,
) {
    fun statusFor(languageTag: String): AndroidOnDeviceLanguageStatus? {
        val normalized = normalizeAndroidLanguageTag(languageTag) ?: return null
        return languages.firstOrNull { languageTagsExactlyMatch(it.languageTag, normalized) }
    }

    val installedLanguageTags: List<String>
        get() = languages.tagsWith(AndroidOnDeviceLanguageAvailability.INSTALLED)

    val pendingLanguageTags: List<String>
        get() = languages.tagsWith(AndroidOnDeviceLanguageAvailability.PENDING)

    val downloadableLanguageTags: List<String>
        get() = languages.tagsWith(AndroidOnDeviceLanguageAvailability.DOWNLOADABLE)

    val unsupportedLanguageTags: List<String>
        get() = languages.tagsWith(AndroidOnDeviceLanguageAvailability.UNSUPPORTED)

    private fun List<AndroidOnDeviceLanguageStatus>.tagsWith(
        availability: AndroidOnDeviceLanguageAvailability,
    ) = filter { it.availability == availability }.map(AndroidOnDeviceLanguageStatus::languageTag)
}

enum class AndroidOnDeviceLanguageSupportUnavailableReason {
    ANDROID_VERSION,
    ON_DEVICE_RECOGNIZER,
}

enum class AndroidOnDeviceLanguageOperation {
    AVAILABILITY,
    SUPPORT_CHECK,
    DOWNLOAD,
}

data class AndroidOnDeviceLanguageOperationError(
    val operation: AndroidOnDeviceLanguageOperation,
    /** A [SpeechRecognizer] error code when the platform supplied one. */
    val platformCode: Int? = null,
    /** The language associated with the failed support check or download, if applicable. */
    val languageTag: String? = null,
)

sealed interface AndroidOnDeviceLanguageSupportState {
    data object Idle : AndroidOnDeviceLanguageSupportState
    data object Checking : AndroidOnDeviceLanguageSupportState

    /** Android 31-32 provide an on-device engine but cannot report installed language packs. */
    data class Unverified(val requestedLanguageTags: List<String>) :
        AndroidOnDeviceLanguageSupportState

    data class Verified(val support: AndroidOnDeviceLanguageSupport) :
        AndroidOnDeviceLanguageSupportState

    data class Unavailable(val reason: AndroidOnDeviceLanguageSupportUnavailableReason) :
        AndroidOnDeviceLanguageSupportState

    data class Error(val error: AndroidOnDeviceLanguageOperationError) :
        AndroidOnDeviceLanguageSupportState

    data object Closed : AndroidOnDeviceLanguageSupportState
}

sealed interface AndroidOnDeviceLanguageDownloadState {
    data object Idle : AndroidOnDeviceLanguageDownloadState

    data class Starting(val languageTags: List<String>) : AndroidOnDeviceLanguageDownloadState

    /** Progress is available starting on Android 14. */
    data class Progress(
        val languageTag: String,
        val completedPercent: Int,
        val completedLanguageTags: List<String>,
        /** Languages already completed or handed to Android before [languageTag]. */
        val processedLanguageCount: Int,
        val totalLanguageCount: Int,
    ) : AndroidOnDeviceLanguageDownloadState

    /** Android 13 accepted the one-way request; callers should refresh support to verify it. */
    data class Requested(val languageTags: List<String>) : AndroidOnDeviceLanguageDownloadState

    /** Android accepted the request but will complete it outside this process. */
    data class Scheduled(
        val scheduledLanguageTags: List<String>,
        val completedLanguageTags: List<String>,
    ) : AndroidOnDeviceLanguageDownloadState

    data class Complete(
        val completedLanguageTags: List<String>,
        val scheduledLanguageTags: List<String>,
    ) : AndroidOnDeviceLanguageDownloadState

    data class Error(
        val error: AndroidOnDeviceLanguageOperationError,
        val completedLanguageTags: List<String>,
        val scheduledLanguageTags: List<String>,
    ) : AndroidOnDeviceLanguageDownloadState

    data object Closed : AndroidOnDeviceLanguageDownloadState
}

/**
 * Immutable callback payload suitable for assigning directly to Compose `mutableStateOf`.
 * Callbacks are always dispatched on the main thread.
 */
data class AndroidOnDeviceLanguagePackSnapshot(
    val support: AndroidOnDeviceLanguageSupportState = AndroidOnDeviceLanguageSupportState.Idle,
    val download: AndroidOnDeviceLanguageDownloadState = AndroidOnDeviceLanguageDownloadState.Idle,
)

fun interface AndroidOnDeviceLanguagePackCallback {
    fun onStateChanged(snapshot: AndroidOnDeviceLanguagePackSnapshot)
}

/**
 * Checks and downloads Android on-device speech language packs without taking microphone access.
 *
 * All platform recognizers are created, invoked, and destroyed on the main thread. The public
 * methods are safe to call from any thread. A new operation supersedes any previous operation,
 * and [close] invalidates callbacks that the speech service may deliver late.
 */
class AndroidOnDeviceLanguagePackManager internal constructor(
    context: Context,
    requestedLanguageTags: List<String>,
    private val callback: AndroidOnDeviceLanguagePackCallback,
    private val watchdogScheduler: WatchdogScheduler,
) : AutoCloseable {
    constructor(
        context: Context,
        requestedLanguageTags: List<String> = listOf("hi-IN", "en-IN"),
        callback: AndroidOnDeviceLanguagePackCallback,
    ) : this(
        context = context,
        requestedLanguageTags = requestedLanguageTags,
        callback = callback,
        watchdogScheduler = MainThreadWatchdogScheduler(),
    )

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        if (Looper.myLooper() == Looper.getMainLooper()) command.run()
        else mainHandler.post(command)
    }
    private val closeRequested = AtomicBoolean(false)
    private val requestedLanguageTags = canonicalRequestedLanguageTags(requestedLanguageTags)

    // The following fields are only mutated on the main thread.
    private var generation = 0L
    private val activeRecognizers = mutableSetOf<SpeechRecognizer>()
    private val activeWatchdogs = mutableSetOf<ResettableWatchdog>()

    @Volatile
    var currentSnapshot = AndroidOnDeviceLanguagePackSnapshot()
        private set

    /** Refreshes support for each exact requested language tag. */
    fun refreshSupport() = runOnMain {
        if (closeRequested.get()) return@runOnMain
        val operationGeneration = beginOperation()
        updateSnapshot(
            AndroidOnDeviceLanguagePackSnapshot(
                support = AndroidOnDeviceLanguageSupportState.Checking,
                download = AndroidOnDeviceLanguageDownloadState.Idle,
            ),
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Unavailable(
                    AndroidOnDeviceLanguageSupportUnavailableReason.ANDROID_VERSION,
                ),
            )
            return@runOnMain
        }

        val isAvailable = try {
            Api31.isOnDeviceRecognitionAvailable(context)
        } catch (_: RuntimeException) {
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Error(
                    AndroidOnDeviceLanguageOperationError(
                        AndroidOnDeviceLanguageOperation.AVAILABILITY,
                    ),
                ),
            )
            return@runOnMain
        }
        if (!isAvailable) {
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Unavailable(
                    AndroidOnDeviceLanguageSupportUnavailableReason.ON_DEVICE_RECOGNIZER,
                ),
            )
            return@runOnMain
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Unverified(requestedLanguageTags),
            )
            return@runOnMain
        }

        checkSupportApi33(
            operationGeneration = operationGeneration,
            remaining = ArrayDeque(requestedLanguageTags),
            collected = mutableListOf(),
        )
    }

    /** Downloads every language in the most recent verified result marked [AndroidOnDeviceLanguageAvailability.DOWNLOADABLE]. */
    fun downloadMissingLanguagePacks() = runOnMain {
        if (closeRequested.get()) return@runOnMain
        val verified = currentSnapshot.support as? AndroidOnDeviceLanguageSupportState.Verified
        val languageTags = verified?.support?.downloadableLanguageTags.orEmpty()
        val operationGeneration = beginOperation()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || verified == null) {
            publishDownloadError(
                operationGeneration,
                AndroidOnDeviceLanguageOperationError(AndroidOnDeviceLanguageOperation.DOWNLOAD),
            )
            return@runOnMain
        }
        if (languageTags.isEmpty()) {
            publishDownload(
                operationGeneration,
                AndroidOnDeviceLanguageDownloadState.Complete(emptyList(), emptyList()),
            )
            return@runOnMain
        }

        publishDownload(
            operationGeneration,
            AndroidOnDeviceLanguageDownloadState.Starting(languageTags),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            downloadApi34(
                operationGeneration,
                remaining = ArrayDeque(languageTags),
                completed = mutableListOf(),
                scheduled = mutableListOf(),
            )
        } else {
            downloadApi33(operationGeneration, languageTags)
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        runOnMain(allowAfterClose = true) {
            generation++
            cancelAllWatchdogs()
            destroyAllRecognizers()
            updateSnapshot(
                AndroidOnDeviceLanguagePackSnapshot(
                    support = AndroidOnDeviceLanguageSupportState.Closed,
                    download = AndroidOnDeviceLanguageDownloadState.Closed,
                ),
                notify = false,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupportApi33(
        operationGeneration: Long,
        remaining: ArrayDeque<String>,
        collected: MutableList<AndroidOnDeviceLanguageStatus>,
    ) {
        if (!isCurrent(operationGeneration)) return
        val languageTag = remaining.removeFirstOrNull()
        if (languageTag == null) {
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Verified(
                    AndroidOnDeviceLanguageSupport(collected.toList()),
                ),
            )
            return
        }
        val recognizer = createRecognizerOrPublishError(
            operationGeneration,
            AndroidOnDeviceLanguageOperation.SUPPORT_CHECK,
            languageTag,
        ) ?: return
        val intent = recognitionIntentFor(languageTag)
        var terminalCallbackReceived = false
        val watchdog = createWatchdog(SUPPORT_CHECK_TIMEOUT_MILLIS) {
            if (terminalCallbackReceived || !isCurrent(operationGeneration)) return@createWatchdog
            terminalCallbackReceived = true
            destroyRecognizer(recognizer)
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Error(
                    AndroidOnDeviceLanguageOperationError(
                        operation = AndroidOnDeviceLanguageOperation.SUPPORT_CHECK,
                        languageTag = languageTag,
                    ),
                ),
            )
        }
        watchdog.refresh()
        try {
            Api33.checkRecognitionSupport(
                recognizer,
                intent,
                mainExecutor,
                handleResult = { installed, pending, supported ->
                    if (terminalCallbackReceived || !isCurrent(operationGeneration)) {
                        return@checkRecognitionSupport
                    }
                    terminalCallbackReceived = true
                    releaseWatchdog(watchdog)
                    destroyRecognizer(recognizer)
                    val status = deriveAndroidOnDeviceLanguageSupport(
                        requestedLanguageTags = listOf(languageTag),
                        installedLanguageTags = installed,
                        pendingLanguageTags = pending,
                        supportedLanguageTags = supported,
                    ).languages.single()
                    collected += status
                    checkSupportApi33(
                        operationGeneration = operationGeneration,
                        remaining = remaining,
                        collected = collected,
                    )
                },
                handleError = { platformCode ->
                    if (terminalCallbackReceived || !isCurrent(operationGeneration)) {
                        return@checkRecognitionSupport
                    }
                    terminalCallbackReceived = true
                    releaseWatchdog(watchdog)
                    destroyRecognizer(recognizer)
                    publishSupport(
                        operationGeneration,
                        AndroidOnDeviceLanguageSupportState.Error(
                            AndroidOnDeviceLanguageOperationError(
                                operation = AndroidOnDeviceLanguageOperation.SUPPORT_CHECK,
                                platformCode = platformCode,
                                languageTag = languageTag,
                            ),
                        ),
                    )
                },
            )
        } catch (_: RuntimeException) {
            if (terminalCallbackReceived) return
            terminalCallbackReceived = true
            releaseWatchdog(watchdog)
            destroyRecognizer(recognizer)
            publishSupport(
                operationGeneration,
                AndroidOnDeviceLanguageSupportState.Error(
                    AndroidOnDeviceLanguageOperationError(
                        operation = AndroidOnDeviceLanguageOperation.SUPPORT_CHECK,
                        languageTag = languageTag,
                    ),
                ),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun downloadApi33(operationGeneration: Long, languageTags: List<String>) {
        val scheduled = mutableListOf<String>()
        for (languageTag in languageTags) {
            if (!isCurrent(operationGeneration)) return
            val recognizer = createRecognizerOrPublishDownloadError(
                operationGeneration,
                languageTag,
                scheduled,
            ) ?: return
            try {
                Api33.triggerModelDownload(recognizer, recognitionIntentFor(languageTag))
                scheduled += languageTag
            } catch (_: RuntimeException) {
                destroyRecognizer(recognizer)
                publishDownloadError(
                    operationGeneration,
                    AndroidOnDeviceLanguageOperationError(
                        operation = AndroidOnDeviceLanguageOperation.DOWNLOAD,
                        languageTag = languageTag,
                    ),
                    scheduled = scheduled,
                )
                return
            }
            // API 33 has no event listener. The platform owns the scheduled work after this call.
            destroyRecognizer(recognizer)
        }
        publishDownload(
            operationGeneration,
            AndroidOnDeviceLanguageDownloadState.Requested(scheduled.toList()),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun downloadApi34(
        operationGeneration: Long,
        remaining: ArrayDeque<String>,
        completed: MutableList<String>,
        scheduled: MutableList<String>,
    ) {
        if (!isCurrent(operationGeneration)) return
        val languageTag = remaining.removeFirstOrNull()
        if (languageTag == null) {
            publishDownload(
                operationGeneration,
                terminalAndroidOnDeviceLanguageDownloadState(
                    completedLanguageTags = completed,
                    scheduledLanguageTags = scheduled,
                ),
            )
            return
        }

        val recognizer = createRecognizerOrPublishDownloadError(
            operationGeneration,
            languageTag,
            completed = completed,
            scheduled = scheduled,
        ) ?: return
        var terminalCallbackReceived = false
        val watchdog = createWatchdog(DOWNLOAD_NO_PROGRESS_TIMEOUT_MILLIS) {
            if (terminalCallbackReceived || !isCurrent(operationGeneration)) return@createWatchdog
            terminalCallbackReceived = true
            destroyRecognizer(recognizer)
            // Android's listener contract does not guarantee a progress callback. A silent OEM may
            // have accepted the download and continue it out of process, so keep this as a
            // check-again state rather than telling the user it failed. Do not claim languages that
            // have not yet been submitted to Android.
            publishDownload(
                operationGeneration,
                silentAndroidLanguageDownloadTimeoutState(
                    languageTag = languageTag,
                    completedLanguageTags = completed,
                    scheduledLanguageTags = scheduled,
                ),
            )
        }
        fun finishOne(wasScheduled: Boolean) {
            if (terminalCallbackReceived || !isCurrent(operationGeneration)) return
            terminalCallbackReceived = true
            releaseWatchdog(watchdog)
            destroyRecognizer(recognizer)
            if (wasScheduled) scheduled += languageTag else completed += languageTag
            if (wasScheduled) {
                publishDownload(
                    operationGeneration,
                    AndroidOnDeviceLanguageDownloadState.Scheduled(
                        scheduledLanguageTags = scheduled.toList(),
                        completedLanguageTags = completed.toList(),
                    ),
                )
            }
            downloadApi34(operationGeneration, remaining, completed, scheduled)
        }

        watchdog.refresh()
        try {
            Api34.triggerModelDownload(
                recognizer = recognizer,
                intent = recognitionIntentFor(languageTag),
                executor = mainExecutor,
                handleProgress = { percent ->
                    if (!terminalCallbackReceived && isCurrent(operationGeneration)) {
                        // This is a stall watchdog, not a total download limit. Every OEM progress
                        // callback moves the deadline forward, even if the rounded percent repeats.
                        watchdog.refresh()
                        publishDownload(
                            operationGeneration,
                            AndroidOnDeviceLanguageDownloadState.Progress(
                                languageTag = languageTag,
                                completedPercent = percent.coerceIn(0, 100),
                                completedLanguageTags = completed.toList(),
                                processedLanguageCount = completed.size + scheduled.size,
                                totalLanguageCount = completed.size + scheduled.size + remaining.size + 1,
                            ),
                        )
                    }
                },
                handleSuccess = { finishOne(wasScheduled = false) },
                handleScheduled = { finishOne(wasScheduled = true) },
                handleError = downloadError@{ platformCode ->
                    if (terminalCallbackReceived || !isCurrent(operationGeneration)) return@downloadError
                    terminalCallbackReceived = true
                    releaseWatchdog(watchdog)
                    destroyRecognizer(recognizer)
                    publishDownloadError(
                        operationGeneration,
                        AndroidOnDeviceLanguageOperationError(
                            operation = AndroidOnDeviceLanguageOperation.DOWNLOAD,
                            platformCode = platformCode,
                            languageTag = languageTag,
                        ),
                        completed = completed,
                        scheduled = scheduled,
                    )
                },
            )
        } catch (_: RuntimeException) {
            if (!terminalCallbackReceived) {
                terminalCallbackReceived = true
                releaseWatchdog(watchdog)
                destroyRecognizer(recognizer)
                publishDownloadError(
                    operationGeneration,
                    AndroidOnDeviceLanguageOperationError(
                        operation = AndroidOnDeviceLanguageOperation.DOWNLOAD,
                        languageTag = languageTag,
                    ),
                    completed = completed,
                    scheduled = scheduled,
                )
            }
        }
    }

    private fun recognitionIntentFor(languageTag: String): Intent =
        AndroidOnDeviceRecognitionConfig(
            primaryLanguageTag = languageTag,
            allowedLanguageTags = listOf(languageTag),
            enableLanguageSwitch = false,
        ).toIntent()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createRecognizerOrPublishError(
        operationGeneration: Long,
        operation: AndroidOnDeviceLanguageOperation,
        languageTag: String? = null,
    ): SpeechRecognizer? = try {
        Api31.createOnDeviceSpeechRecognizer(context).also(activeRecognizers::add)
    } catch (_: RuntimeException) {
        publishSupport(
            operationGeneration,
            AndroidOnDeviceLanguageSupportState.Error(
                AndroidOnDeviceLanguageOperationError(
                    operation = operation,
                    languageTag = languageTag,
                ),
            ),
        )
        null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createRecognizerOrPublishDownloadError(
        operationGeneration: Long,
        languageTag: String,
        completed: List<String> = emptyList(),
        scheduled: List<String> = emptyList(),
    ): SpeechRecognizer? = try {
        Api31.createOnDeviceSpeechRecognizer(context).also(activeRecognizers::add)
    } catch (_: RuntimeException) {
        publishDownloadError(
            operationGeneration,
            AndroidOnDeviceLanguageOperationError(
                operation = AndroidOnDeviceLanguageOperation.DOWNLOAD,
                languageTag = languageTag,
            ),
            completed = completed,
            scheduled = scheduled,
        )
        null
    }

    private fun beginOperation(): Long {
        cancelAllWatchdogs()
        generation++
        destroyAllRecognizers()
        return generation
    }

    private fun isCurrent(operationGeneration: Long): Boolean =
        !closeRequested.get() && operationGeneration == generation

    private fun publishSupport(
        operationGeneration: Long,
        state: AndroidOnDeviceLanguageSupportState,
    ) {
        if (!isCurrent(operationGeneration)) return
        updateSnapshot(currentSnapshot.copy(support = state))
    }

    private fun publishDownload(
        operationGeneration: Long,
        state: AndroidOnDeviceLanguageDownloadState,
    ) {
        if (!isCurrent(operationGeneration)) return
        updateSnapshot(currentSnapshot.copy(download = state))
    }

    private fun publishDownloadError(
        operationGeneration: Long,
        error: AndroidOnDeviceLanguageOperationError,
        completed: List<String> = emptyList(),
        scheduled: List<String> = emptyList(),
    ) = publishDownload(
        operationGeneration,
        AndroidOnDeviceLanguageDownloadState.Error(
            error = error,
            completedLanguageTags = completed.toList(),
            scheduledLanguageTags = scheduled.toList(),
        ),
    )

    private fun updateSnapshot(
        snapshot: AndroidOnDeviceLanguagePackSnapshot,
        notify: Boolean = true,
    ) {
        currentSnapshot = snapshot
        if (notify) runCatching { callback.onStateChanged(snapshot) }
    }

    private fun destroyRecognizer(recognizer: SpeechRecognizer) {
        activeRecognizers.remove(recognizer)
        runCatching(recognizer::destroy)
    }

    private fun destroyAllRecognizers() {
        val recognizers = activeRecognizers.toList()
        activeRecognizers.clear()
        recognizers.forEach { runCatching(it::destroy) }
    }

    private fun createWatchdog(
        timeoutMillis: Long,
        onTimeout: () -> Unit,
    ): ResettableWatchdog {
        lateinit var watchdog: ResettableWatchdog
        watchdog = ResettableWatchdog(watchdogScheduler, timeoutMillis) {
            activeWatchdogs.remove(watchdog)
            onTimeout()
        }
        activeWatchdogs += watchdog
        return watchdog
    }

    private fun releaseWatchdog(watchdog: ResettableWatchdog) {
        watchdog.cancel()
        activeWatchdogs.remove(watchdog)
    }

    private fun cancelAllWatchdogs() {
        activeWatchdogs.forEach(ResettableWatchdog::cancel)
        activeWatchdogs.clear()
    }

    private fun runOnMain(allowAfterClose: Boolean = false, block: () -> Unit) {
        if (!allowAfterClose && closeRequested.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post {
            if (allowAfterClose || !closeRequested.get()) block()
        }
    }

    private companion object {
        const val SUPPORT_CHECK_TIMEOUT_MILLIS = 5_000L
        const val DOWNLOAD_NO_PROGRESS_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * A scheduled Android download is not an installed model. Preserve that distinction at the end
 * of a multi-language operation so the UI cannot briefly advertise a background request as ready.
 */
internal fun terminalAndroidOnDeviceLanguageDownloadState(
    completedLanguageTags: List<String>,
    scheduledLanguageTags: List<String>,
): AndroidOnDeviceLanguageDownloadState = if (scheduledLanguageTags.isNotEmpty()) {
    AndroidOnDeviceLanguageDownloadState.Scheduled(
        scheduledLanguageTags = scheduledLanguageTags.toList(),
        completedLanguageTags = completedLanguageTags.toList(),
    )
} else {
    AndroidOnDeviceLanguageDownloadState.Complete(
        completedLanguageTags = completedLanguageTags.toList(),
        scheduledLanguageTags = emptyList(),
    )
}

/** Converts per-language OEM progress into monotonic progress for the whole requested queue. */
internal fun overallAndroidLanguageDownloadPercent(
    processedLanguageCount: Int,
    currentLanguagePercent: Int,
    totalLanguageCount: Int,
): Int {
    if (totalLanguageCount <= 0) return 0
    val processed = processedLanguageCount.coerceIn(0, totalLanguageCount)
    val current = currentLanguagePercent.coerceIn(0, 100)
    return ((processed * 100L + current) / totalLanguageCount)
        .coerceIn(0L, 100L)
        .toInt()
}

/** A silent listener is treated as one submitted background request, never as a failed queue. */
internal fun silentAndroidLanguageDownloadTimeoutState(
    languageTag: String,
    completedLanguageTags: List<String>,
    scheduledLanguageTags: List<String>,
): AndroidOnDeviceLanguageDownloadState.Scheduled =
    AndroidOnDeviceLanguageDownloadState.Scheduled(
        scheduledLanguageTags = (scheduledLanguageTags + languageTag).distinct(),
        completedLanguageTags = completedLanguageTags.toList(),
    )

/** Pure exact-match derivation kept separate from the Android callback lifecycle for unit testing. */
internal fun deriveAndroidOnDeviceLanguageSupport(
    requestedLanguageTags: List<String>,
    installedLanguageTags: List<String>,
    pendingLanguageTags: List<String>,
    supportedLanguageTags: List<String>,
): AndroidOnDeviceLanguageSupport {
    val requested = canonicalRequestedLanguageTags(requestedLanguageTags)
    val installed = normalizedLanguageTagKeys(installedLanguageTags)
    val pending = normalizedLanguageTagKeys(pendingLanguageTags)
    val supported = normalizedLanguageTagKeys(supportedLanguageTags)
    return AndroidOnDeviceLanguageSupport(
        requested.map { languageTag ->
            val key = languageTagKey(languageTag)
            val availability = when (key) {
                in installed -> AndroidOnDeviceLanguageAvailability.INSTALLED
                in pending -> AndroidOnDeviceLanguageAvailability.PENDING
                in supported -> AndroidOnDeviceLanguageAvailability.DOWNLOADABLE
                else -> AndroidOnDeviceLanguageAvailability.UNSUPPORTED
            }
            AndroidOnDeviceLanguageStatus(languageTag, availability)
        },
    )
}

internal fun normalizeAndroidLanguageTag(languageTag: String): String? {
    val candidate = languageTag.trim().replace('_', '-')
    if (candidate.isEmpty()) return null
    val locale = try {
        Locale.Builder().setLanguageTag(candidate).build()
    } catch (_: IllformedLocaleException) {
        return null
    }
    val normalized = locale.toLanguageTag()
    return normalized.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
}

private fun canonicalRequestedLanguageTags(languageTags: List<String>): List<String> {
    require(languageTags.isNotEmpty()) { "At least one language tag is required" }
    val normalized = languageTags.map { languageTag ->
        requireNotNull(normalizeAndroidLanguageTag(languageTag)) {
            "Invalid BCP-47 language tag: $languageTag"
        }
    }
    return normalized.distinctBy(::languageTagKey)
}

private fun normalizedLanguageTagKeys(languageTags: List<String>): Set<String> =
    languageTags.mapNotNull(::normalizeAndroidLanguageTag).mapTo(mutableSetOf(), ::languageTagKey)

private fun languageTagsExactlyMatch(first: String, second: String): Boolean =
    languageTagKey(first) == languageTagKey(second)

private fun languageTagKey(languageTag: String): String = languageTag.lowercase(Locale.ROOT)

@RequiresApi(Build.VERSION_CODES.S)
private object Api31 {
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun createOnDeviceSpeechRecognizer(context: Context): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object Api33 {
    fun checkRecognitionSupport(
        recognizer: SpeechRecognizer,
        intent: Intent,
        executor: Executor,
        handleResult: (installed: List<String>, pending: List<String>, supported: List<String>) -> Unit,
        handleError: (Int) -> Unit,
    ) {
        recognizer.checkRecognitionSupport(
            intent,
            executor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    handleResult(
                        recognitionSupport.installedOnDeviceLanguages.toList(),
                        recognitionSupport.pendingOnDeviceLanguages.toList(),
                        recognitionSupport.supportedOnDeviceLanguages.toList(),
                    )
                }

                override fun onError(error: Int) = handleError(error)
            },
        )
    }

    fun triggerModelDownload(recognizer: SpeechRecognizer, intent: Intent) {
        recognizer.triggerModelDownload(intent)
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object Api34 {
    fun triggerModelDownload(
        recognizer: SpeechRecognizer,
        intent: Intent,
        executor: Executor,
        handleProgress: (Int) -> Unit,
        handleSuccess: () -> Unit,
        handleScheduled: () -> Unit,
        handleError: (Int) -> Unit,
    ) {
        recognizer.triggerModelDownload(
            intent,
            executor,
            object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = handleProgress(completedPercent)
                override fun onSuccess() = handleSuccess()
                override fun onScheduled() = handleScheduled()
                override fun onError(error: Int) = handleError(error)
            },
        )
    }
}
