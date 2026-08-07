// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class LocalModelInstallError {
    UNKNOWN_MODEL,
    NOT_DOWNLOADABLE,
    NOT_ENOUGH_SPACE,
    NETWORK,
    HTTP,
    INTEGRITY,
    UNSAFE_ARCHIVE,
    INVALID_MODEL_LAYOUT,
    FILESYSTEM,
}

class LocalModelInstallException(
    val error: LocalModelInstallError,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class LocalModelInstallPhase {
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INSTALLING,
    COMPLETE,
}

data class LocalModelInstallProgress(
    val phase: LocalModelInstallPhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

data class InstalledLocalModel(
    val descriptor: LocalSpeechModelDescriptor,
    val directory: File,
)

/** All local model paths are descendants of app-private no-backup storage. */
class LocalModelPaths private constructor(
    val baseDirectory: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "$ROOT_DIRECTORY/$LAYOUT_VERSION"),
    )

    internal constructor(noBackupDirectory: File, forTests: Boolean) : this(
        if (forTests) File(noBackupDirectory, "$ROOT_DIRECTORY/$LAYOUT_VERSION")
        else error("The test-only constructor requires forTests=true"),
    )

    val downloadsDirectory: File get() = safeChild(baseDirectory, "downloads")
    val stagingDirectory: File get() = safeChild(baseDirectory, "staging")
    val installedDirectory: File get() = safeChild(baseDirectory, "installed")
    val locksDirectory: File get() = safeChild(baseDirectory, "locks")

    internal fun partialArchive(artifact: LocalModelArtifact): File =
        safeChild(downloadsDirectory, "${artifact.archiveFileName}.part")

    internal fun verifiedArchive(artifact: LocalModelArtifact): File =
        safeChild(downloadsDirectory, "${artifact.archiveFileName}.verified")

    internal fun installedModelDirectory(artifact: LocalModelArtifact): File =
        safeChild(installedDirectory, artifact.installationDirectory)

    internal fun stagingModelDirectory(artifact: LocalModelArtifact): File =
        safeChild(stagingDirectory, "${artifact.installationDirectory}.staging")

    internal fun lockFile(artifact: LocalModelArtifact): File =
        safeChild(locksDirectory, "${artifact.installationDirectory}.lock")

    internal fun ensureDirectories() {
        listOf(
            baseDirectory,
            downloadsDirectory,
            stagingDirectory,
            installedDirectory,
            locksDirectory,
        ).forEach { directory ->
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw LocalModelInstallException(
                    LocalModelInstallError.FILESYSTEM,
                    retryable = false,
                    message = "Could not create local model storage",
                )
            }
        }
    }

    private fun safeChild(parent: File, name: String): File {
        require('/' !in name && '\\' !in name && name != "." && name != "..") {
            "Unsafe local model path component"
        }
        val child = File(parent, name)
        val parentPath = parent.canonicalFile.path + File.separator
        require(child.canonicalFile.path.startsWith(parentPath)) { "Path escaped local model storage" }
        return child
    }

    companion object {
        const val ROOT_DIRECTORY = "speech-models"
        const val LAYOUT_VERSION = "v1"
    }
}

internal fun interface AvailableSpaceProvider {
    fun availableBytes(directory: File): Long
}

internal object StatFsAvailableSpaceProvider : AvailableSpaceProvider {
    override fun availableBytes(directory: File): Long = StatFs(directory.absolutePath).availableBytes
}

internal object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

/**
 * A JVM lock prevents overlapping locks in one process; the file lock covers a future secondary
 * process. Worker download/install and explicit deletion use the same lock.
 */
internal object LocalModelFileLock {
    private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(lockFile: File, block: () -> T): T {
        val processLock = processLocks.getOrPut(lockFile.absolutePath) { ReentrantLock() }
        return processLock.withLock {
            val lockDirectory = requireNotNull(lockFile.parentFile)
            if (!lockDirectory.mkdirs() && !lockDirectory.isDirectory) {
                throw LocalModelInstallException(
                    LocalModelInstallError.FILESYSTEM,
                    retryable = false,
                    message = "Could not create local model lock directory",
                )
            }
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                val fileLock: FileLock = channel.lock()
                fileLock.use { block() }
            }
        }
    }
}

/** Deletion does not follow a symlink that may have appeared inside app-private storage. */
internal object SafeLocalFiles {
    fun deleteTree(root: File): Boolean {
        if (!root.exists() && !isSymbolicLink(root)) return true
        if (isSymbolicLink(root) || root.isFile) return root.delete()
        val children = root.listFiles() ?: return false
        if (children.any { !deleteTree(it) }) return false
        return root.delete()
    }

    private fun isSymbolicLink(file: File): Boolean {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
        } catch (_: Throwable) {
            // Host-side unit tests do not provide android.system. Resolve the leaf against its
            // canonical parent so a symlink in an ancestor (for example /var -> /private/var on
            // macOS) is not mistaken for a symlink at this path.
            return try {
                val canonicalParent = file.parentFile?.canonicalFile ?: return false
                val leafFromCanonicalParent = File(canonicalParent, file.name)
                leafFromCanonicalParent.absoluteFile != leafFromCanonicalParent.canonicalFile
            } catch (_: Exception) {
                true
            }
        }
    }
}

internal fun interface AtomicDirectoryMover {
    fun move(source: File, destination: File)
}

/** Source and destination are deliberately siblings on the same no-backup filesystem. */
internal object PosixAtomicDirectoryMover : AtomicDirectoryMover {
    override fun move(source: File, destination: File) {
        try {
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (failure: ErrnoException) {
            throw LocalModelInstallException(
                LocalModelInstallError.FILESYSTEM,
                retryable = true,
                message = "Could not atomically install the local speech model",
                cause = failure,
            )
        }
    }
}

internal object LocalModelInstallMarker {
    private const val FILE_NAME = ".speakkeys-model.properties"
    private const val KEY_STABLE_ID = "stableId"
    private const val KEY_ARCHIVE_SHA256 = "archiveSha256"
    private const val KEY_EXTRACTED_BYTES = "extractedBytes"

    fun write(modelDirectory: File, descriptor: LocalSpeechModelDescriptor) {
        val artifact = requireNotNull(descriptor.artifact)
        val properties = Properties().apply {
            setProperty(KEY_STABLE_ID, descriptor.stableId)
            setProperty(KEY_ARCHIVE_SHA256, artifact.archiveSha256)
            setProperty(KEY_EXTRACTED_BYTES, artifact.extractedBytes.toString())
        }
        val marker = File(modelDirectory, FILE_NAME)
        FileOutputStream(marker).use { output ->
            properties.store(output, "SpeakKeys verified local speech model")
            output.fd.sync()
        }
    }

    fun matches(modelDirectory: File, descriptor: LocalSpeechModelDescriptor): Boolean {
        val artifact = descriptor.artifact ?: return false
        if (!modelDirectory.isDirectory) return false
        val marker = File(modelDirectory, FILE_NAME)
        if (!marker.isFile) return false
        val properties = try {
            Properties().also { result -> marker.inputStream().use(result::load) }
        } catch (_: Exception) {
            return false
        }
        if (properties.getProperty(KEY_STABLE_ID) != descriptor.stableId ||
            properties.getProperty(KEY_ARCHIVE_SHA256) != artifact.archiveSha256 ||
            properties.getProperty(KEY_EXTRACTED_BYTES)?.toLongOrNull() != artifact.extractedBytes
        ) {
            return false
        }
        return artifact.requiredRelativeFiles.all { relativePath ->
            val required = File(modelDirectory, relativePath)
            required.isFile && isContained(modelDirectory, required)
        }
    }

    private fun isContained(parent: File, child: File): Boolean = try {
        child.canonicalFile.path.startsWith(parent.canonicalFile.path + File.separator)
    } catch (_: Exception) {
        false
    }
}
