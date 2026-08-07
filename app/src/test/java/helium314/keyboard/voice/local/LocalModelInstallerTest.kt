// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LocalModelInstallerTest {
    private lateinit var temporaryDirectory: File
    private lateinit var paths: LocalModelPaths

    @BeforeTest
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("speakkeys-model-storage").toFile()
        paths = LocalModelPaths(temporaryDirectory, forTests = true)
    }

    @AfterTest
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun failsBeforeNetworkOrExtractionWhenSafeInstallSpaceIsUnavailable() {
        val installer = LocalModelInstaller(
            paths = paths,
            availableSpaceProvider = AvailableSpaceProvider { 0L },
        )

        val failure = expectInstallFailure {
            installer.install(LocalSpeechModelCatalog.VOSK_HINDI_SMALL_ID)
        }

        assertEquals(LocalModelInstallError.NOT_ENOUGH_SPACE, failure.error)
        assertFalse(failure.retryable)
        assertTrue(paths.downloadsDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(paths.installedDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun installedStatusRequiresMarkerDigestAndRequiredLayout() {
        val descriptor = LocalSpeechModelCatalog.voskHindiSmall
        val artifact = requireNotNull(descriptor.artifact)
        paths.ensureDirectories()
        val directory = paths.installedModelDirectory(artifact)
        artifact.requiredRelativeFiles.forEach { relativePath ->
            File(directory, relativePath).apply {
                parentFile?.mkdirs()
                writeText("test")
            }
        }
        LocalModelInstallMarker.write(directory, descriptor)
        val installer = LocalModelInstaller(
            paths = paths,
            availableSpaceProvider = AvailableSpaceProvider { Long.MAX_VALUE },
        )

        assertNotNull(installer.installedModel(descriptor.stableId))

        File(directory, artifact.requiredRelativeFiles.first()).delete()
        assertEquals(null, installer.installedModel(descriptor.stableId))
    }

    @Test
    fun deleteRemovesInstalledPartialVerifiedAndStagedData() {
        val descriptor = LocalSpeechModelCatalog.voskHindiSmall
        val artifact = requireNotNull(descriptor.artifact)
        paths.ensureDirectories()
        listOf(
            paths.installedModelDirectory(artifact),
            paths.stagingModelDirectory(artifact),
        ).forEach { directory -> File(directory, "file").apply { parentFile?.mkdirs(); writeText("x") } }
        paths.partialArchive(artifact).writeText("partial")
        paths.verifiedArchive(artifact).writeText("verified")
        val installer = LocalModelInstaller(
            paths = paths,
            availableSpaceProvider = AvailableSpaceProvider { Long.MAX_VALUE },
        )

        assertTrue(installer.delete(descriptor.stableId))

        assertFalse(paths.installedModelDirectory(artifact).exists())
        assertFalse(paths.stagingModelDirectory(artifact).exists())
        assertFalse(paths.partialArchive(artifact).exists())
        assertFalse(paths.verifiedArchive(artifact).exists())
    }

    private fun expectInstallFailure(block: () -> Unit): LocalModelInstallException = try {
        block()
        fail("Expected LocalModelInstallException")
    } catch (failure: LocalModelInstallException) {
        failure
    }
}
