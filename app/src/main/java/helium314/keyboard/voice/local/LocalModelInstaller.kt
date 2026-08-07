// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Blocking install core. WorkManager invokes it on Dispatchers.IO; keeping it independent from
 * WorkManager makes integrity and extraction behavior directly testable.
 */
class LocalModelInstaller internal constructor(
    private val paths: LocalModelPaths,
    private val downloader: ResumableModelDownloader = ResumableModelDownloader(),
    private val extractor: SecureModelArchiveExtractor = SecureModelArchiveExtractor(),
    private val availableSpaceProvider: AvailableSpaceProvider = StatFsAvailableSpaceProvider,
    private val directoryMover: AtomicDirectoryMover = PosixAtomicDirectoryMover,
) {
    constructor(context: Context) : this(LocalModelPaths(context))

    fun install(
        stableId: String,
        shouldContinue: () -> Boolean = { true },
        onProgress: (LocalModelInstallProgress) -> Unit = {},
    ): InstalledLocalModel {
        val descriptor = LocalSpeechModelCatalog.find(stableId)
            ?: throw LocalModelInstallException(
                LocalModelInstallError.UNKNOWN_MODEL,
                retryable = false,
                message = "Unknown local speech model",
            )
        val artifact = descriptor.artifact
            ?: throw LocalModelInstallException(
                LocalModelInstallError.NOT_DOWNLOADABLE,
                retryable = false,
                message = "This speech engine is managed by Android",
            )

        paths.ensureDirectories()
        return LocalModelFileLock.withLock(paths.lockFile(artifact)) {
            checkCancelled(shouldContinue)
            onProgress(LocalModelInstallProgress(LocalModelInstallPhase.CHECKING))
            val installedDirectory = paths.installedModelDirectory(artifact)
            if (LocalModelInstallMarker.matches(installedDirectory, descriptor)) {
                onProgress(
                    LocalModelInstallProgress(
                        LocalModelInstallPhase.COMPLETE,
                        artifact.archiveBytes,
                        artifact.archiveBytes,
                    ),
                )
                return@withLock InstalledLocalModel(descriptor, installedDirectory)
            }

            val verifiedArchive = prepareVerifiedArchive(
                artifact = artifact,
                shouldContinue = shouldContinue,
                onProgress = onProgress,
            )

            ensureFreeSpace(
                requiredBytes = checkedAdd(artifact.extractedBytes, FREE_SPACE_RESERVE_BYTES),
                directory = paths.baseDirectory,
            )
            val staging = paths.stagingModelDirectory(artifact)
            try {
                onProgress(
                    LocalModelInstallProgress(
                        LocalModelInstallPhase.EXTRACTING,
                        totalBytes = artifact.extractedBytes,
                    ),
                )
                extractor.extract(
                    archive = verifiedArchive,
                    destination = staging,
                    artifact = artifact,
                    shouldContinue = shouldContinue,
                ) { extracted, total ->
                    onProgress(
                        LocalModelInstallProgress(
                            LocalModelInstallPhase.EXTRACTING,
                            extracted,
                            total,
                        ),
                    )
                }
                checkCancelled(shouldContinue)

                val stagedModel = File(staging, artifact.archiveRootDirectory)
                LocalModelInstallMarker.write(stagedModel, descriptor)
                onProgress(LocalModelInstallProgress(LocalModelInstallPhase.INSTALLING))

                if (installedDirectory.exists() && !SafeLocalFiles.deleteTree(installedDirectory)) {
                    throw filesystemFailure("Could not replace an invalid local model installation")
                }
                directoryMover.move(stagedModel, installedDirectory)
                if (!LocalModelInstallMarker.matches(installedDirectory, descriptor)) {
                    SafeLocalFiles.deleteTree(installedDirectory)
                    throw LocalModelInstallException(
                        LocalModelInstallError.INVALID_MODEL_LAYOUT,
                        retryable = false,
                        message = "Installed local model failed final validation",
                    )
                }

                verifiedArchive.delete()
                onProgress(
                    LocalModelInstallProgress(
                        LocalModelInstallPhase.COMPLETE,
                        artifact.archiveBytes,
                        artifact.archiveBytes,
                    ),
                )
                return@withLock InstalledLocalModel(descriptor, installedDirectory)
            } catch (failure: LocalModelInstallException) {
                throw failure
            } catch (failure: IOException) {
                throw filesystemFailure("Could not install local speech model", failure)
            } finally {
                SafeLocalFiles.deleteTree(staging)
            }
        }
    }

    fun installedModel(stableId: String): InstalledLocalModel? {
        val descriptor = LocalSpeechModelCatalog.find(stableId) ?: return null
        val artifact = descriptor.artifact ?: return null
        val directory = paths.installedModelDirectory(artifact)
        return if (LocalModelInstallMarker.matches(directory, descriptor)) {
            InstalledLocalModel(descriptor, directory)
        } else {
            null
        }
    }

    fun installedModels(): List<InstalledLocalModel> =
        LocalSpeechModelCatalog.downloadable.mapNotNull { installedModel(it.stableId) }

    /** Deletes the installed model and any resumable/staged data while holding the install lock. */
    fun delete(stableId: String): Boolean {
        val descriptor = LocalSpeechModelCatalog.find(stableId) ?: return false
        val artifact = descriptor.artifact ?: return false
        paths.ensureDirectories()
        return LocalModelFileLock.withLock(paths.lockFile(artifact)) {
            listOf(
                paths.installedModelDirectory(artifact),
                paths.partialArchive(artifact),
                paths.verifiedArchive(artifact),
                paths.stagingModelDirectory(artifact),
            ).map(SafeLocalFiles::deleteTree).all { it }
        }
    }

    private fun prepareVerifiedArchive(
        artifact: LocalModelArtifact,
        shouldContinue: () -> Boolean,
        onProgress: (LocalModelInstallProgress) -> Unit,
    ): File {
        val verified = paths.verifiedArchive(artifact)
        if (verified.exists()) {
            onProgress(LocalModelInstallProgress(LocalModelInstallPhase.VERIFYING))
            if (isVerifiedArchive(verified, artifact)) return verified
            if (!verified.delete()) throw filesystemFailure("Could not discard corrupt model archive")
        }

        val partial = paths.partialArchive(artifact)
        if (partial.isFile && partial.length() == artifact.archiveBytes) {
            onProgress(LocalModelInstallProgress(LocalModelInstallPhase.VERIFYING))
            if (isVerifiedArchive(partial, artifact)) {
                if (!partial.renameTo(verified)) {
                    throw filesystemFailure("Could not finalize verified model archive")
                }
                return verified
            }
            if (!partial.delete()) throw filesystemFailure("Could not discard corrupt model archive")
        }

        val existingBytes = partial.takeIf(File::isFile)?.length()?.coerceAtMost(artifact.archiveBytes) ?: 0L
        val remainingDownload = artifact.archiveBytes - existingBytes
        val requiredSpace = checkedAdd(
            checkedAdd(remainingDownload, artifact.extractedBytes),
            FREE_SPACE_RESERVE_BYTES,
        )
        ensureFreeSpace(requiredSpace, paths.baseDirectory)

        onProgress(
            LocalModelInstallProgress(
                LocalModelInstallPhase.DOWNLOADING,
                existingBytes,
                artifact.archiveBytes,
            ),
        )
        downloader.download(
            artifact = artifact,
            partialFile = partial,
            shouldContinue = shouldContinue,
        ) { downloaded, total ->
            onProgress(
                LocalModelInstallProgress(
                    LocalModelInstallPhase.DOWNLOADING,
                    downloaded,
                    total,
                ),
            )
        }
        checkCancelled(shouldContinue)
        onProgress(
            LocalModelInstallProgress(
                LocalModelInstallPhase.VERIFYING,
                artifact.archiveBytes,
                artifact.archiveBytes,
            ),
        )
        if (!isVerifiedArchive(partial, artifact)) {
            partial.delete()
            throw LocalModelInstallException(
                LocalModelInstallError.INTEGRITY,
                retryable = false,
                message = "Downloaded model failed final verification",
            )
        }
        if (verified.exists() && !verified.delete()) {
            throw filesystemFailure("Could not replace staged model archive")
        }
        if (!partial.renameTo(verified)) {
            throw filesystemFailure("Could not finalize verified model archive")
        }
        return verified
    }

    private fun isVerifiedArchive(file: File, artifact: LocalModelArtifact): Boolean =
        file.isFile && file.length() == artifact.archiveBytes && Sha256.of(file) == artifact.archiveSha256

    private fun ensureFreeSpace(requiredBytes: Long, directory: File) {
        val available = availableSpaceProvider.availableBytes(directory)
        if (available < requiredBytes) {
            throw LocalModelInstallException(
                LocalModelInstallError.NOT_ENOUGH_SPACE,
                retryable = false,
                message = "Not enough free storage to safely install this speech model",
            )
        }
    }

    private fun checkCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw IOException("Local model installation was cancelled")
    }

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (failure: ArithmeticException) {
        throw LocalModelInstallException(
            LocalModelInstallError.INTEGRITY,
            retryable = false,
            message = "Local model size overflowed",
            cause = failure,
        )
    }

    private fun filesystemFailure(message: String, cause: Throwable? = null) =
        LocalModelInstallException(
            LocalModelInstallError.FILESYSTEM,
            retryable = true,
            message = message,
            cause = cause,
        )

    companion object {
        private const val FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
    }
}
