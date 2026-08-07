// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import helium314.keyboard.voice.local.LocalSpeechModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelManagerLifecycleGateTest {
    @Test
    fun micPressCoalescesWithPrewarmBeforeSourcePublishesLoading() {
        val source = FakeSource()
        val tracker = RecognizerInitializationTracker()
        val prewarm = assertNotNull(tracker.request(source, autoStart = false, attributionContext = null))

        assertNull(tracker.request(source, autoStart = true, attributionContext = null))

        val completion = tracker.complete(prewarm)
        assertTrue(completion.valid)
        assertSame(source, completion.deferredStart?.source)
    }

    @Test
    fun cancelBeforeInitializationCompletesCannotStartCapture() {
        val source = FakeSource()
        val tracker = RecognizerInitializationTracker()
        val token = assertNotNull(tracker.request(source, autoStart = true, attributionContext = null))

        tracker.invalidate()

        assertFalse(tracker.complete(token).valid)
        assertFalse(tracker.hasPendingFor(source))
    }

    @Test
    fun aFreshInitializationAfterCancelGetsANewGeneration() {
        val source = FakeSource()
        val tracker = RecognizerInitializationTracker()
        val cancelled = assertNotNull(tracker.request(source, autoStart = true, attributionContext = null))
        tracker.invalidate()

        val replacement = assertNotNull(
            tracker.request(source, autoStart = true, attributionContext = null),
        )

        assertNotSame(cancelled, replacement)
        assertTrue(replacement.generation > cancelled.generation)
        assertTrue(tracker.complete(replacement).valid)
    }

    @Test
    fun replacementCaptureWaitsUntilEveryPriorAudioRecordCleanupFinishes() {
        val source = FakeSource()
        val gate = CaptureCleanupStartGate()
        val deferred = DeferredRecognizerStart(source, null)
        gate.cleanupScheduled()
        gate.cleanupScheduled()

        assertTrue(gate.deferIfNeeded(deferred))
        assertNull(gate.cleanupFinished())
        assertSame(deferred, gate.cleanupFinished())
    }

    @Test
    fun cancelDropsCaptureDeferredDuringCleanup() {
        val gate = CaptureCleanupStartGate()
        gate.cleanupScheduled()
        assertTrue(gate.deferIfNeeded(DeferredRecognizerStart(FakeSource(), null)))

        gate.cancelDeferredStart()

        assertNull(gate.cleanupFinished())
    }

    @Test
    fun incompleteProviderDiscoveryNeverProducesPreferencesToPersist() {
        val selectedCloud = model("cloud://selected", ModelType.ProxiedSarvamCloud)
        val local = model(LocalSpeechModelCatalog.VOSK_HINDI_SMALL_ID, ModelType.VoskLocal)

        val reconciliation = reconcileRecognizerCatalog(
            currentModels = listOf(selectedCloud, local),
            installedModels = listOf(local),
            authoritative = false,
        )

        assertEquals(listOf(local), reconciliation.sessionModels)
        assertNull(reconciliation.modelsToPersist)
    }

    @Test
    fun lockedOrCorruptCredentialStorageCannotOverwriteSelection() {
        var writes = 0

        val persisted = persistProviderCatalogIfAuthoritative(authoritative = false) { writes++ }

        assertFalse(persisted)
        assertEquals(0, writes)
        assertTrue(persistProviderCatalogIfAuthoritative(authoritative = true) { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun authoritativeDiscoveryPersistsRefreshedMetadataInUserOrder() {
        val oldLocal = model(LocalSpeechModelCatalog.VOSK_HINDI_SMALL_ID, ModelType.VoskLocal, "Old")
        val refreshedLocal = oldLocal.copy(name = "Fresh")
        val cloud = model("cloud://sarvam", ModelType.SarvamCloud)

        val reconciliation = reconcileRecognizerCatalog(
            currentModels = listOf(oldLocal),
            installedModels = listOf(cloud, refreshedLocal),
            authoritative = true,
        )

        assertEquals(listOf(refreshedLocal, cloud), reconciliation.sessionModels)
        assertEquals(reconciliation.sessionModels, reconciliation.modelsToPersist)
    }

    @Test
    fun onlyImmutableVoskSourcesAreRetainedAcrossEditorReloads() {
        assertTrue(
            canReuseRecognizerSourceAcrossReload(
                model(LocalSpeechModelCatalog.VOSK_HINDI_SMALL_ID, ModelType.VoskLocal),
            ),
        )
        assertFalse(
            canReuseRecognizerSourceAcrossReload(
                model(LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID, ModelType.AndroidOnDevice),
            ),
        )
        assertFalse(canReuseRecognizerSourceAcrossReload(model("cloud://sarvam", ModelType.SarvamCloud)))
    }

    private fun model(path: String, type: ModelType, name: String = path) =
        InstalledModelReference(path = path, name = name, type = type)

    private class FakeSource : RecognizerSource {
        override suspend fun initialize() = Unit
        override val recognizer: Recognizer
            get() = error("Not used")
        override fun close(freeRAM: Boolean) = Unit
        override val stateFlow: StateFlow<RecognizerState> = MutableStateFlow(RecognizerState.READY)
        override val addSpaces: Boolean = true
        override val closed: Boolean = false
        override val errorMessage: String = ""
        override val name: String = "Test"
        override val locale: SpeakKeysLocale = SpeakKeysLocale.ROOT
    }
}
