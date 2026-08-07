// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class SecureModelArchiveExtractorTest {
    private lateinit var temporaryDirectory: File

    @BeforeTest
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("speakkeys-model-extract").toFile()
    }

    @AfterTest
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun extractsExpectedRootAndRequiredFiles() {
        val archive = zipOf("test-model/am/final.mdl" to "model".encodeToByteArray())
        val destination = File(temporaryDirectory, "staging")
        val artifact = artifact(extractedBytes = 5L)

        val result = SecureModelArchiveExtractor().extract(archive, destination, artifact)

        assertEquals(1, result.entryCount)
        assertEquals(5L, result.extractedBytes)
        assertEquals("model", File(destination, "test-model/am/final.mdl").readText())
    }

    @Test
    fun rejectsZipSlipAndRemovesPartialExtraction() {
        val archive = zipOf("test-model/../../escaped" to byteArrayOf(1))
        val destination = File(temporaryDirectory, "staging")

        val failure = expectInstallFailure {
            SecureModelArchiveExtractor().extract(archive, destination, artifact(1L))
        }

        assertEquals(LocalModelInstallError.UNSAFE_ARCHIVE, failure.error)
        assertFalse(File(temporaryDirectory, "escaped").exists())
        assertFalse(destination.exists())
    }

    @Test
    fun rejectsAbsoluteAndBackslashPaths() {
        listOf(
            "/test-model/am/final.mdl",
            "test-model\\am\\final.mdl",
        ).forEachIndexed { index, entryName ->
            val archive = zipOf(entryName to byteArrayOf(1), name = "unsafe-$index.zip")
            val failure = expectInstallFailure {
                SecureModelArchiveExtractor().extract(
                    archive,
                    File(temporaryDirectory, "staging-$index"),
                    artifact(1L),
                )
            }
            assertEquals(LocalModelInstallError.UNSAFE_ARCHIVE, failure.error)
        }
    }

    @Test
    fun rejectsUnexpectedRoot() {
        val archive = zipOf("other-model/am/final.mdl" to byteArrayOf(1))

        val failure = expectInstallFailure {
            SecureModelArchiveExtractor().extract(
                archive,
                File(temporaryDirectory, "staging"),
                artifact(1L),
            )
        }

        assertEquals(LocalModelInstallError.UNSAFE_ARCHIVE, failure.error)
    }

    @Test
    fun enforcesPinnedExpandedSize() {
        val archive = zipOf("test-model/am/final.mdl" to byteArrayOf(1, 2, 3, 4, 5))

        val failure = expectInstallFailure {
            SecureModelArchiveExtractor().extract(
                archive,
                File(temporaryDirectory, "staging"),
                artifact(extractedBytes = 4L),
            )
        }

        assertEquals(LocalModelInstallError.UNSAFE_ARCHIVE, failure.error)
        assertTrue(failure.message.orEmpty().contains("limit"))
    }

    @Test
    fun validatesRequiredModelLayoutAfterExtraction() {
        val archive = zipOf("test-model/README" to "x".encodeToByteArray())

        val failure = expectInstallFailure {
            SecureModelArchiveExtractor().extract(
                archive,
                File(temporaryDirectory, "staging"),
                artifact(extractedBytes = 1L),
            )
        }

        assertEquals(LocalModelInstallError.INVALID_MODEL_LAYOUT, failure.error)
    }

    private fun artifact(extractedBytes: Long) = LocalModelArtifact(
        url = "https://example.test/test-model.zip",
        archiveFileName = "test-model.zip",
        archiveSha256 = "0".repeat(64),
        archiveBytes = 1L,
        extractedBytes = extractedBytes,
        archiveRootDirectory = "test-model",
        installationDirectory = "test-model-v1",
        requiredRelativeFiles = setOf("am/final.mdl"),
    )

    private fun zipOf(
        vararg entries: Pair<String, ByteArray>,
        name: String = "model.zip",
    ): File {
        val archive = File(temporaryDirectory, name)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (entryName, bytes) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun expectInstallFailure(block: () -> Unit): LocalModelInstallException = try {
        block()
        fail("Expected LocalModelInstallException")
    } catch (failure: LocalModelInstallException) {
        failure
    }
}
