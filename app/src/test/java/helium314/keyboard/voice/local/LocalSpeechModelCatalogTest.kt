// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class LocalSpeechModelCatalogTest {
    @Test
    fun hindiArtifactIsPinnedToVerifiedOfficialArchive() {
        val model = LocalSpeechModelCatalog.voskHindiSmall
        val artifact = requireNotNull(model.artifact)

        assertEquals("vosk://hi-small-0.22", model.stableId)
        assertEquals(
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            artifact.url,
        )
        assertEquals(44_458_845L, artifact.archiveBytes)
        assertEquals(82_084_166L, artifact.extractedBytes)
        assertEquals(
            "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48",
            artifact.archiveSha256,
        )
        assertEquals(AudioOwnership.APP_PCM, model.audioOwnership)
        assertFalse(model.supportsLanguageSwitch)
    }

    @Test
    fun indianEnglishArtifactIsPinnedToVerifiedOfficialArchive() {
        val model = LocalSpeechModelCatalog.voskEnglishIndiaSmall
        val artifact = requireNotNull(model.artifact)

        assertEquals("vosk://en-in-small-0.4", model.stableId)
        assertEquals(
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            artifact.url,
        )
        assertEquals(37_573_330L, artifact.archiveBytes)
        assertEquals(56_700_192L, artifact.extractedBytes)
        assertEquals(
            "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7",
            artifact.archiveSha256,
        )
    }

    @Test
    fun systemRecognizerIsEngineOwnedAndHasNoDownload() {
        val model = LocalSpeechModelCatalog.androidOnDevice

        assertEquals("android://on-device", model.stableId)
        assertEquals(AudioOwnership.ENGINE_MIC, model.audioOwnership)
        assertNull(model.artifact)
        assertFalse(model.supportsLanguageSwitch)
        assertSame(model, LocalSpeechModelCatalog.find(model.stableId))
    }
}
