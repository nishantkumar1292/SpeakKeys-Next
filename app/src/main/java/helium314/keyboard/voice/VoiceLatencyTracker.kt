package helium314.keyboard.voice

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures latency boundaries without retaining audio or transcript content.
 * The sample can be logged locally or fed to a consented diagnostics screen.
 */
internal class VoiceLatencyTracker(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val nextUtteranceId = AtomicLong(0)
    private var active: ActiveUtterance? = null

    @Synchronized
    fun onMicStarted(): Long {
        val utteranceId = nextUtteranceId.incrementAndGet()
        active = ActiveUtterance(id = utteranceId, micStartedAt = nowMillis())
        return utteranceId
    }

    @Synchronized
    fun onFirstPartial() {
        val utterance = active ?: return
        if (utterance.firstPartialAt == null) {
            utterance.firstPartialAt = nowMillis()
        }
    }

    @Synchronized
    fun onReleased() {
        val utterance = active ?: return
        if (utterance.releasedAt == null) {
            utterance.releasedAt = nowMillis()
        }
    }

    @Synchronized
    fun onFinal(): VoiceLatencySample? {
        val utterance = active ?: return null
        active = null
        val finalAt = nowMillis()
        return VoiceLatencySample(
            utteranceId = utterance.id,
            micToFirstPartialMs = utterance.firstPartialAt?.minus(utterance.micStartedAt),
            releaseToFinalMs = utterance.releasedAt?.let(finalAt::minus),
            totalMs = finalAt - utterance.micStartedAt,
        )
    }

    @Synchronized
    fun onCancelled() {
        active = null
    }

    private data class ActiveUtterance(
        val id: Long,
        val micStartedAt: Long,
        var firstPartialAt: Long? = null,
        var releasedAt: Long? = null,
    )
}

internal data class VoiceLatencySample(
    val utteranceId: Long,
    val micToFirstPartialMs: Long?,
    val releaseToFinalMs: Long?,
    val totalMs: Long,
)
