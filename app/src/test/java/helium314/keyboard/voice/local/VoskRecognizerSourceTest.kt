// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoskRecognizerSourceTest {
    private lateinit var temporaryDirectory: File

    @BeforeTest
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("speakkeys-vosk-source").toFile()
    }

    @AfterTest
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun nonFreeingCloseDoesNotResetRecognizerAfterUiBecomesRestartable() = runBlocking {
        val descriptor = descriptor()
        val requiredFile = File(temporaryDirectory, "am/final.mdl")
        requiredFile.parentFile?.mkdirs()
        requiredFile.writeText("test")
        LocalModelInstallMarker.write(temporaryDirectory, descriptor)
        val backend = FakeBackend()
        val nativeModel = FakeCloseable()
        val adapter = VoskRecognizerAdapter(backend, "hi")
        val source = VoskRecognizerSource(
            installedModel = InstalledLocalModel(descriptor, temporaryDirectory),
            runtimeLoader = VoskRuntimeLoader { _, _ -> LoadedVoskRuntime(nativeModel, adapter) },
        )

        source.initialize()
        source.close(freeRAM = false)

        assertEquals(0, backend.resetCount)
        assertEquals(0, nativeModel.closeCount)
        assertFalse(source.closed)
        assertEquals(RecognizerState.IN_RAM, source.stateFlow.value)

        // MySpeechService owns the one reset at the beginning of the next utterance.
        source.recognizer.reset()
        assertEquals(1, backend.resetCount)

        source.close(freeRAM = true)
        assertTrue(source.closed)
        assertEquals(1, backend.closeCount)
        assertEquals(1, nativeModel.closeCount)
    }

    private fun descriptor(): LocalSpeechModelDescriptor {
        val artifact = LocalModelArtifact(
            url = "https://example.test/model.zip",
            archiveFileName = "model.zip",
            archiveSha256 = "0".repeat(64),
            archiveBytes = 1L,
            extractedBytes = 4L,
            archiveRootDirectory = "model",
            installationDirectory = "model-v1",
            requiredRelativeFiles = setOf("am/final.mdl"),
        )
        return LocalSpeechModelDescriptor(
            stableId = "vosk://test",
            runtime = LocalSpeechRuntime.VOSK,
            displayName = "Test Vosk",
            shortDescription = "Test",
            languageTags = listOf("hi-IN"),
            speechStyle = SpeechStyle.MOSTLY_HINDI,
            audioOwnership = AudioOwnership.APP_PCM,
            supportsStreamingResults = true,
            supportsLanguageSwitch = false,
            license = LocalModelLicense("Test", "https://example.test/license"),
            artifact = artifact,
        )
    }

    private class FakeBackend : VoskRecognizerBackend {
        var resetCount = 0
        var closeCount = 0

        override fun reset() {
            resetCount++
        }

        override fun acceptWaveForm(buffer: ShortArray, length: Int): Boolean = false
        override fun result(): String = """{"text":""}"""
        override fun partialResult(): String = """{"partial":""}"""
        override fun finalResult(): String = """{"text":""}"""
        override fun close() {
            closeCount++
        }
    }

    private class FakeCloseable : AutoCloseable {
        var closeCount = 0
        override fun close() {
            closeCount++
        }
    }
}
