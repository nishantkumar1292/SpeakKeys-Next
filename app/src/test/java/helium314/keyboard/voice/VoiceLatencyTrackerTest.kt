package helium314.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceLatencyTrackerTest {
    @Test
    fun measuresFirstPartialReleaseAndFinalBoundaries() {
        var now = 1_000L
        val tracker = VoiceLatencyTracker { now }

        assertEquals(1L, tracker.onMicStarted())
        now = 1_125L
        tracker.onFirstPartial()
        now = 1_200L
        tracker.onFirstPartial() // only the first provisional result is measured
        now = 1_900L
        tracker.onReleased()
        now = 2_050L

        assertEquals(
            VoiceLatencySample(
                utteranceId = 1L,
                micToFirstPartialMs = 125L,
                releaseToFinalMs = 150L,
                totalMs = 1_050L,
            ),
            tracker.onFinal(),
        )
        assertNull(tracker.onFinal())
    }

    @Test
    fun cancellationDiscardsTheActiveSample() {
        var now = 0L
        val tracker = VoiceLatencyTracker { now }

        tracker.onMicStarted()
        now = 40L
        tracker.onFirstPartial()
        tracker.onCancelled()

        assertNull(tracker.onFinal())
        assertEquals(2L, tracker.onMicStarted())
    }
}
