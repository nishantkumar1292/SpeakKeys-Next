// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal data class ExtractionStats(
    val entryCount: Int,
    val extractedBytes: Long,
)

/** Extracts only the pinned root of a verified archive and caps all output. */
internal class SecureModelArchiveExtractor(
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    fun extract(
        archive: File,
        destination: File,
        artifact: LocalModelArtifact,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ExtractionStats {
        if (destination.exists() && !SafeLocalFiles.deleteTree(destination)) {
            throw filesystemFailure("Could not clear model staging directory")
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw filesystemFailure("Could not create model staging directory")
        }

        val destinationCanonical = destination.canonicalFile
        val destinationPrefix = destinationCanonical.path + File.separator
        val seenPaths = HashSet<String>()
        var entries = 0
        var extracted = 0L

        try {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                while (true) {
                    checkCancelled(shouldContinue)
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > maximumEntries) {
                        throw unsafeArchive("Model archive contains too many entries")
                    }

                    val components = validateEntryName(entry.name, artifact.archiveRootDirectory)
                    val normalized = components.joinToString("/")
                    if (!seenPaths.add(normalized)) {
                        throw unsafeArchive("Model archive contains duplicate entries")
                    }

                    val target = File(destination, components.joinToString(File.separator))
                    val targetCanonical = target.canonicalFile
                    if (!targetCanonical.path.startsWith(destinationPrefix)) {
                        throw unsafeArchive("Model archive entry escapes staging storage")
                    }

                    if (entry.isDirectory || entry.name.endsWith('/')) {
                        if (!target.mkdirs() && !target.isDirectory) {
                            throw filesystemFailure("Could not create extracted model directory")
                        }
                    } else {
                        val declaredSize = entry.size
                        if (declaredSize > artifact.extractedBytes) {
                            throw unsafeArchive("Model archive entry exceeds the extraction limit")
                        }
                        val parent = requireNotNull(target.parentFile)
                        if (!parent.mkdirs() && !parent.isDirectory) {
                            throw filesystemFailure("Could not create extracted model directory")
                        }
                        if (target.exists()) {
                            throw unsafeArchive("Model archive would overwrite an existing path")
                        }

                        FileOutputStream(target).use { fileOutput ->
                            val output = BufferedOutputStream(fileOutput)
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                checkCancelled(shouldContinue)
                                val read = zip.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                extracted = checkedAdd(extracted, read.toLong())
                                if (extracted > artifact.extractedBytes) {
                                    throw unsafeArchive("Model archive exceeds the extraction limit")
                                }
                                output.write(buffer, 0, read)
                                onProgress(extracted, artifact.extractedBytes)
                            }
                            output.flush()
                            fileOutput.fd.sync()
                        }
                    }
                    zip.closeEntry()
                }
            }

            if (extracted != artifact.extractedBytes) {
                throw unsafeArchive(
                    "Extracted model size was $extracted bytes; expected ${artifact.extractedBytes}",
                )
            }

            val modelRoot = File(destination, artifact.archiveRootDirectory)
            validateModelLayout(modelRoot, artifact)
            return ExtractionStats(entries, extracted)
        } catch (failure: LocalModelInstallException) {
            SafeLocalFiles.deleteTree(destination)
            throw failure
        } catch (failure: ZipException) {
            SafeLocalFiles.deleteTree(destination)
            throw unsafeArchive("Model archive is not a valid ZIP file", failure)
        } catch (failure: IOException) {
            SafeLocalFiles.deleteTree(destination)
            throw filesystemFailure("Could not extract local speech model", failure)
        } catch (failure: ArithmeticException) {
            SafeLocalFiles.deleteTree(destination)
            throw unsafeArchive("Model archive size overflowed", failure)
        }
    }

    private fun validateEntryName(name: String, expectedRoot: String): List<String> {
        if (name.isBlank() || name.length > MAXIMUM_ENTRY_NAME_LENGTH ||
            name.startsWith('/') || name.startsWith('\\') || '\\' in name || '\u0000' in name
        ) {
            throw unsafeArchive("Model archive contains an unsafe entry name")
        }
        val withoutTrailingSlash = name.removeSuffix("/")
        val components = withoutTrailingSlash.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) {
            throw unsafeArchive("Model archive contains path traversal")
        }
        if (components.firstOrNull() != expectedRoot) {
            throw unsafeArchive("Model archive has an unexpected root directory")
        }
        return components
    }

    private fun validateModelLayout(modelRoot: File, artifact: LocalModelArtifact) {
        if (!modelRoot.isDirectory) {
            throw LocalModelInstallException(
                LocalModelInstallError.INVALID_MODEL_LAYOUT,
                retryable = false,
                message = "The archive does not contain the expected model directory",
            )
        }
        val rootPrefix = modelRoot.canonicalFile.path + File.separator
        artifact.requiredRelativeFiles.forEach { relativePath ->
            val requiredFile = File(modelRoot, relativePath)
            if (!requiredFile.isFile || !requiredFile.canonicalFile.path.startsWith(rootPrefix)) {
                throw LocalModelInstallException(
                    LocalModelInstallError.INVALID_MODEL_LAYOUT,
                    retryable = false,
                    message = "The archive is missing a required Vosk model file",
                )
            }
        }
    }

    private fun checkCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) {
            throw IOException("Local model installation was cancelled")
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long = Math.addExact(left, right)

    private fun unsafeArchive(
        message: String,
        cause: Throwable? = null,
    ) = LocalModelInstallException(
        LocalModelInstallError.UNSAFE_ARCHIVE,
        retryable = false,
        message = message,
        cause = cause,
    )

    private fun filesystemFailure(
        message: String,
        cause: Throwable? = null,
    ) = LocalModelInstallException(
        LocalModelInstallError.FILESYSTEM,
        retryable = true,
        message = message,
        cause = cause,
    )

    companion object {
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val DEFAULT_MAXIMUM_ENTRIES = 2_048
        private const val MAXIMUM_ENTRY_NAME_LENGTH = 512
    }
}
