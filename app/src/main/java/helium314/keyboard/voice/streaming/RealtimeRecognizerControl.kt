package helium314.keyboard.voice.streaming

/**
 * Lifecycle and diagnostics that are specific to a push-based realtime recognizer.
 *
 * [Recognizer] intentionally stays provider-agnostic, so callers that understand realtime
 * sessions can opt into this small interface without changing the shared KMP contract.
 */
interface RealtimeRecognizerControl {
    val connectionState: RealtimeConnectionState
    val lastFailure: Throwable?

    /** Opens a fresh, idle provider session so the microphone's first frames can be queued. */
    fun prepare()

    /** Invalidates the current session and guarantees that its late callbacks are ignored. */
    fun cancelCurrentUtterance()
}

enum class RealtimeConnectionState {
    IDLE,
    CONNECTING,
    OPEN,
    FINALIZING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

class RealtimeRecognitionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
