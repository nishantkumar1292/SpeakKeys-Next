package helium314.keyboard.settings.screens

import helium314.keyboard.voice.local.LocalModelInstallPhase
import helium314.keyboard.voice.local.LocalSpeechModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceModelPickerLogicTest {
    private val descriptor = LocalSpeechModelCatalog.voskHindiSmall
    private val artifact = requireNotNull(descriptor.artifact)
    private val downloadWeight = artifact.archiveBytes.toFloat() /
        (artifact.archiveBytes + artifact.extractedBytes).toFloat()

    @Test
    fun progressDoesNotJumpBackWhenExtractionStarts() {
        assertEquals(
            downloadWeight,
            requireNotNull(
                overallInstallProgress(descriptor, LocalModelInstallPhase.VERIFYING, null),
            ),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            downloadWeight,
            requireNotNull(
                overallInstallProgress(descriptor, LocalModelInstallPhase.EXTRACTING, 0f),
            ),
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun progressCombinesDownloadAndExtractionWork() {
        assertEquals(
            downloadWeight * 0.5f,
            requireNotNull(
                overallInstallProgress(descriptor, LocalModelInstallPhase.DOWNLOADING, 0.5f),
            ),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            downloadWeight + (1f - downloadWeight) * 0.5f,
            requireNotNull(
                overallInstallProgress(descriptor, LocalModelInstallPhase.EXTRACTING, 0.5f),
            ),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            1f,
            requireNotNull(
                overallInstallProgress(descriptor, LocalModelInstallPhase.COMPLETE, 1f),
            ),
        )
    }

    @Test
    fun absentWorkHasNoSyntheticProgress() {
        assertNull(overallInstallProgress(descriptor, null, null))
    }

    @Test
    fun retainedArchiveBytesAreNotChargedTwiceInStorageEstimate() {
        val withoutResume = requiredInstallBytes(descriptor, 0L)
        val halfArchive = artifact.archiveBytes / 2L

        assertEquals(
            withoutResume - halfArchive,
            requiredInstallBytes(descriptor, halfArchive),
        )
        assertEquals(
            withoutResume - artifact.archiveBytes,
            requiredInstallBytes(descriptor, Long.MAX_VALUE),
        )
        assertTrue(requiredInstallBytes(descriptor, -1L) == withoutResume)
    }
}
