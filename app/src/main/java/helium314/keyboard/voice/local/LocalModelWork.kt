// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Scheduling/query facade for local model lifecycle. It never accepts a caller-provided URL. */
class LocalModelRepository(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val installer: LocalModelInstaller = LocalModelInstaller(context.applicationContext),
) {
    private val applicationContext = context.applicationContext
    private val paths = LocalModelPaths(applicationContext)
    private val workRequests = applicationContext.getSharedPreferences(
        WORK_REQUEST_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun enqueueInstall(stableId: String, allowMeteredNetwork: Boolean): UUID {
        val descriptor = LocalSpeechModelCatalog.find(stableId)
            ?: throw IllegalArgumentException("Unknown local speech model: $stableId")
        val artifact = descriptor.artifact
            ?: throw IllegalArgumentException("Android-managed recognition has no app download")

        val networkType = if (allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<LocalModelInstallWorker>()
            .setInputData(workDataOf(LocalModelInstallWorker.KEY_MODEL_ID to descriptor.stableId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(TAG_ALL_LOCAL_MODEL_INSTALLS)
            .addTag(workTag(artifact))
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(artifact),
            ExistingWorkPolicy.KEEP,
            request,
        )
        // WorkManager does not promise an ordering for getWorkInfosForUniqueWork(). Persist the
        // request identity so a recreated settings screen follows the work the user most recently
        // asked for instead of an arbitrary historical result.
        workRequests.edit { putString(workRequestKey(artifact), request.id.toString()) }
        return request.id
    }

    fun lastRequestedWorkId(stableId: String): String? {
        val artifact = requireArtifact(stableId)
        return workRequests.getString(workRequestKey(artifact), null)
    }

    fun installWork(stableId: String): LiveData<List<WorkInfo>> {
        val artifact = requireArtifact(stableId)
        return workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(artifact))
    }

    /** Stops active work but preserves its verified/partial archive so a later request can resume. */
    fun pauseInstall(stableId: String) {
        val artifact = requireArtifact(stableId)
        workManager.cancelUniqueWork(uniqueWorkName(artifact))
    }

    fun installedModel(stableId: String): InstalledLocalModel? = installer.installedModel(stableId)

    fun installedModels(): List<InstalledLocalModel> = installer.installedModels()

    /** Bytes already kept on-device that a future install can reuse. */
    fun resumableArchiveBytes(stableId: String): Long {
        val artifact = requireArtifact(stableId)
        val verified = paths.verifiedArchive(artifact)
        if (verified.isFile && verified.length() == artifact.archiveBytes) {
            return artifact.archiveBytes
        }
        return paths.partialArchive(artifact)
            .takeIf(File::isFile)
            ?.length()
            ?.coerceIn(0L, artifact.archiveBytes)
            ?: 0L
    }

    suspend fun delete(stableId: String): Boolean = withContext(Dispatchers.IO) {
        val artifact = requireArtifact(stableId)
        try {
            // Await acknowledgement before taking the same cross-process install lock.
            workManager.cancelUniqueWork(uniqueWorkName(artifact)).result.get()
        } catch (_: Exception) {
            // The shared file lock still prevents delete/install overlap if cancellation reporting fails.
        }
        installer.delete(stableId)
    }

    private fun requireArtifact(stableId: String): LocalModelArtifact =
        LocalSpeechModelCatalog.find(stableId)?.artifact
            ?: throw IllegalArgumentException("Unknown or non-downloadable local model: $stableId")

    companion object {
        const val TAG_ALL_LOCAL_MODEL_INSTALLS = "local-speech-model-install"
        private const val WORK_REQUEST_PREFERENCES = "local-speech-model-work-requests"

        internal fun uniqueWorkName(artifact: LocalModelArtifact): String =
            "local-speech-model:${artifact.installationDirectory}"

        private fun workTag(artifact: LocalModelArtifact): String =
            "local-speech-model:${artifact.installationDirectory}:download"

        private fun workRequestKey(artifact: LocalModelArtifact): String =
            "${artifact.installationDirectory}:last-request-id"
    }
}

class LocalModelInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val stableId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext failure(
                LocalModelInstallError.UNKNOWN_MODEL,
                "Missing local model ID",
            )
        if (LocalSpeechModelCatalog.find(stableId)?.artifact == null) {
            return@withContext failure(
                LocalModelInstallError.UNKNOWN_MODEL,
                "Unknown or non-downloadable local model",
            )
        }

        val installer = LocalModelInstaller(applicationContext)
        val progressReporter = ProgressReporter()
        try {
            val installed = installer.install(
                stableId = stableId,
                shouldContinue = { !isStopped },
            ) { progress ->
                progressReporter.report(progress)
            }
            Result.success(
                workDataOf(
                    KEY_MODEL_ID to installed.descriptor.stableId,
                    KEY_INSTALLED_PATH to installed.directory.absolutePath,
                ),
            )
        } catch (failure: LocalModelInstallException) {
            if (failure.retryable && runAttemptCount < MAXIMUM_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                failure(failure.error, failure.message ?: "Local model installation failed")
            }
        } catch (failure: IOException) {
            if (!isStopped && runAttemptCount < MAXIMUM_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                failure(LocalModelInstallError.FILESYSTEM, "Local model installation was interrupted")
            }
        } catch (_: Exception) {
            failure(LocalModelInstallError.FILESYSTEM, "Local model installation failed")
        }
    }

    private fun LocalModelInstallProgress.toData(): Data = workDataOf(
        KEY_PHASE to phase.name,
        KEY_COMPLETED_BYTES to completedBytes,
        KEY_TOTAL_BYTES to totalBytes,
    )

    private fun failure(error: LocalModelInstallError, message: String): Result =
        Result.failure(
            workDataOf(
                KEY_ERROR to error.name,
                KEY_ERROR_MESSAGE to message.take(MAXIMUM_ERROR_MESSAGE_LENGTH),
            ),
        )

    /** Avoids turning each 32 KiB download/extraction chunk into a WorkManager database write. */
    private inner class ProgressReporter {
        private var lastPhase: LocalModelInstallPhase? = null
        private var lastCompletedBytes = 0L
        private var lastUpdateMillis = 0L

        fun report(progress: LocalModelInstallProgress) {
            val now = SystemClock.elapsedRealtime()
            val phaseChanged = progress.phase != lastPhase
            val enoughBytes = progress.completedBytes - lastCompletedBytes >= PROGRESS_STEP_BYTES
            val enoughTime = now - lastUpdateMillis >= PROGRESS_STEP_MILLIS
            val phaseComplete = progress.totalBytes > 0L &&
                progress.completedBytes >= progress.totalBytes
            if (!phaseChanged && !enoughBytes && !enoughTime && !phaseComplete) return

            setProgressAsync(progress.toData())
            lastPhase = progress.phase
            lastCompletedBytes = progress.completedBytes
            lastUpdateMillis = now
        }
    }

    companion object {
        const val KEY_MODEL_ID = "localModelId"
        const val KEY_INSTALLED_PATH = "installedPath"
        const val KEY_PHASE = "phase"
        const val KEY_COMPLETED_BYTES = "completedBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_MESSAGE = "errorMessage"

        private const val MAXIMUM_RETRY_ATTEMPTS = 5
        private const val MAXIMUM_ERROR_MESSAGE_LENGTH = 160
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private const val PROGRESS_STEP_MILLIS = 500L
    }
}
