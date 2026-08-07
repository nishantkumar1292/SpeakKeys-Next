// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOnDeviceRecognizerControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun startBuildsOfflineLanguageSwitchRequestAndDeliversResults() {
        val platform = FakePlatformRecognizer()
        val callback = RecordingCallback()
        val controller = controller(platform, callback)

        assertEquals(AudioOwnership.ENGINE_MIC, controller.audioOwnership)
        assertTrue(controller.start())
        val intent = requireNotNull(platform.lastIntent)
        assertEquals("hi-IN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(
            RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH),
        )
        val allowedLanguages: ArrayList<String>? = intent.getStringArrayListExtra(
            RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
        )
        assertTrue(allowedLanguages == arrayListOf("hi-IN", "en-IN"))

        platform.listener?.onPartialResults(resultsBundle("namaste wor"))
        platform.listener?.onResults(resultsBundle("namaste world", "alternate"))

        assertEquals("namaste wor", callback.partials.single().text)
        assertEquals("namaste world", callback.finals.single().text)
        assertEquals(listOf("namaste world", "alternate"), callback.finals.single().alternatives)
        assertEquals(AndroidOnDeviceRecognitionState.IDLE, controller.currentState)
    }

    @Test
    fun stopRequestsFinalizationAndMovesToProcessing() {
        val platform = FakePlatformRecognizer()
        val controller = controller(platform, RecordingCallback())

        controller.start()
        assertTrue(controller.stop())

        assertEquals(1, platform.stopCount)
        assertEquals(AndroidOnDeviceRecognitionState.PROCESSING, controller.currentState)
        assertFalse(controller.start())
    }

    @Test
    fun stopFailureEmitsTerminalErrorSoTheOwnerCanReleaseItsSession() {
        val platform = FakePlatformRecognizer(throwOnStop = true)
        val callback = RecordingCallback()
        val controller = controller(platform, callback)

        controller.start()
        assertFalse(controller.stop())

        assertEquals(AndroidOnDeviceRecognitionState.IDLE, controller.currentState)
        assertEquals(AndroidOnDeviceErrorCategory.SERVICE, callback.errors.single().category)
        assertEquals(1, platform.destroyCount)
    }

    @Test
    fun finalizationTimeoutResetsAndAllowsANewSession() {
        val scheduler = FakeWatchdogScheduler(runCancelledTasks = true)
        val firstPlatform = FakePlatformRecognizer()
        val secondPlatform = FakePlatformRecognizer()
        val platforms = ArrayDeque(listOf(firstPlatform, secondPlatform))
        val callback = RecordingCallback()
        val controller = AndroidOnDeviceRecognizerController(
            context = context,
            callback = callback,
            availability = { true },
            factory = PlatformSpeechRecognizerFactory { platforms.removeFirst() },
            watchdogScheduler = scheduler,
        )

        assertTrue(controller.start())
        val staleListener = requireNotNull(firstPlatform.listener)
        assertTrue(controller.stop())
        scheduler.advanceBy(3_999L)
        assertEquals(AndroidOnDeviceRecognitionState.PROCESSING, controller.currentState)
        assertTrue(callback.errors.isEmpty())

        scheduler.advanceBy(1L)
        assertEquals(AndroidOnDeviceRecognitionState.IDLE, controller.currentState)
        assertEquals(
            AndroidOnDeviceRecognizerController.ERROR_FINAL_RESULT_TIMEOUT,
            callback.errors.single().platformCode,
        )
        assertEquals(1, firstPlatform.cancelCount)
        assertEquals(1, firstPlatform.destroyCount)

        staleListener.onResults(resultsBundle("late result"))
        assertTrue(callback.finals.isEmpty())
        assertTrue(controller.start())
        assertTrue(secondPlatform.lastIntent != null)
    }

    @Test
    fun finalResultCancelsTheWatchdogEvenIfACancelledTaskRaces() {
        val scheduler = FakeWatchdogScheduler(runCancelledTasks = true)
        val platform = FakePlatformRecognizer()
        val callback = RecordingCallback()
        val controller = controller(platform, callback, scheduler)

        controller.start()
        controller.stop()
        platform.listener?.onResults(resultsBundle("done"))
        scheduler.advanceBy(10_000L)

        assertEquals(listOf("done"), callback.finals.map { it.text })
        assertTrue(callback.errors.isEmpty())
        assertEquals(AndroidOnDeviceRecognitionState.IDLE, controller.currentState)
        assertEquals(0, platform.destroyCount)
    }

    @Test
    fun cancelDestroysRecognizerAndIgnoresStaleCallbacks() {
        val platform = FakePlatformRecognizer()
        val callback = RecordingCallback()
        val controller = controller(platform, callback)

        controller.start()
        val staleListener = requireNotNull(platform.listener)
        controller.cancel()
        staleListener.onResults(resultsBundle("must not commit"))

        assertEquals(1, platform.cancelCount)
        assertEquals(1, platform.destroyCount)
        assertTrue(callback.finals.isEmpty())
        assertEquals(AndroidOnDeviceRecognitionState.IDLE, controller.currentState)
    }

    @Test
    fun unavailableDeviceNeverCreatesAMicrophoneSession() {
        val platform = FakePlatformRecognizer()
        val callback = RecordingCallback()
        val controller = AndroidOnDeviceRecognizerController(
            context = context,
            callback = callback,
            availability = { false },
            factory = PlatformSpeechRecognizerFactory { platform },
        )

        assertFalse(controller.start())

        assertNull(platform.lastIntent)
        assertEquals(AndroidOnDeviceRecognitionState.UNAVAILABLE, controller.currentState)
        assertEquals(AndroidOnDeviceErrorCategory.SERVICE, callback.errors.single().category)
    }

    private fun controller(
        platform: FakePlatformRecognizer,
        callback: RecordingCallback,
        watchdogScheduler: WatchdogScheduler = FakeWatchdogScheduler(),
    ) = AndroidOnDeviceRecognizerController(
        context = context,
        callback = callback,
        availability = { true },
        factory = PlatformSpeechRecognizerFactory { platform },
        watchdogScheduler = watchdogScheduler,
    )

    private fun resultsBundle(vararg text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*text))
    }

    private class FakePlatformRecognizer(
        private val throwOnStop: Boolean = false,
    ) : PlatformSpeechRecognizer {
        var listener: RecognitionListener? = null
        var lastIntent: Intent? = null
        var stopCount = 0
        var cancelCount = 0
        var destroyCount = 0

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            lastIntent = intent
        }

        override fun stopListening() {
            if (throwOnStop) throw IllegalStateException("platform stop failed")
            stopCount++
        }

        override fun cancel() {
            cancelCount++
        }

        override fun destroy() {
            destroyCount++
        }
    }

    private class RecordingCallback : AndroidOnDeviceRecognitionCallback {
        val states = mutableListOf<AndroidOnDeviceRecognitionState>()
        val partials = mutableListOf<AndroidRecognitionHypothesis>()
        val finals = mutableListOf<AndroidRecognitionHypothesis>()
        val errors = mutableListOf<AndroidOnDeviceRecognitionError>()

        override fun onStateChanged(state: AndroidOnDeviceRecognitionState) {
            states += state
        }

        override fun onPartialResult(result: AndroidRecognitionHypothesis) {
            partials += result
        }

        override fun onFinalResult(result: AndroidRecognitionHypothesis) {
            finals += result
        }

        override fun onError(error: AndroidOnDeviceRecognitionError) {
            errors += error
        }
    }
}
