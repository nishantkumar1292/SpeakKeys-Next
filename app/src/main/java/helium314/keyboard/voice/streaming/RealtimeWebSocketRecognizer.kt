package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Adapts an asynchronous WebSocket transcription API to SpeakKeys' synchronous [Recognizer]
 * contract. One instance is reusable. A pristine pre-opened session survives the recorder's first
 * reset; every subsequent reset gets a distinct socket generation so a late result from an old
 * utterance can never leak into a new input field.
 */
internal abstract class RealtimeWebSocketRecognizer(
    private val client: OkHttpClient,
    private val protocol: RealtimeSpeechProtocol,
    private val finalResultTimeoutMillis: Long,
    maxPreOpenAudioMillis: Int,
) : Recognizer, RealtimeRecognizerControl {

    init {
        require(finalResultTimeoutMillis > 0) { "finalResultTimeoutMillis must be positive" }
        require(maxPreOpenAudioMillis > 0) { "maxPreOpenAudioMillis must be positive" }
    }

    final override val sampleRate: Float = SAMPLE_RATE_HZ.toFloat()

    private val maxPreOpenAudioBytes = maxPreOpenAudioMillis * BYTES_PER_MILLISECOND
    private val lock = Any()
    private val generationCounter = AtomicLong(0)

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    final override var connectionState: RealtimeConnectionState = RealtimeConnectionState.IDLE
        private set

    @Volatile
    final override var lastFailure: Throwable? = null
        private set

    private var prewarmAfterCleanupRequested = false

    final override fun prepare() {
        startFreshSession(claimedByUtterance = false)
    }

    /** Opens a pristine session and waits until the provider, not just the transport, is ready. */
    final fun prepareAndAwaitReady(timeoutMillis: Long) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        startFreshSession(claimedByUtterance = false)
        val session = checkNotNull(activeSession)

        if (!awaitUninterruptibly(session.ready, timeoutMillis)) {
            val timeout = SocketTimeoutException(
                "${protocol.providerName} did not become ready within ${timeoutMillis}ms"
            )
            failSession(session, timeout.message.orEmpty(), timeout)
        }

        synchronized(lock) {
            session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
            if (!isActiveLocked(session) || session.cancelled || !session.providerReady) {
                throw RealtimeRecognitionException(
                    "${protocol.providerName} realtime initialization was cancelled"
                )
            }
        }
    }

    final override fun reset() {
        val preparedSessionCanBeReused = synchronized(lock) {
            activeSession?.let {
                val reusable = !it.hadAudio && !it.finalRequested && !it.completed &&
                    !it.cancelled && it.failure == null
                if (reusable) it.claimedByUtterance = true
                reusable
            } == true
        }
        if (preparedSessionCanBeReused) return
        startFreshSession(claimedByUtterance = true)
    }

    /** Cancellation must close the current socket without opening the replacement that reset does. */
    final override fun cancel() {
        cancelCurrentUtterance()
    }

    /**
     * Closes an unused prewarm or a terminal session without racing a later call to [reset].
     *
     * ModelManager performs source cleanup asynchronously after publishing UI-idle. A rapid next
     * mic press can therefore claim a new generation before that stale cleanup runs. Checking the
     * claim while holding [lock] prevents the old cleanup from cancelling the new utterance, even
     * in the short interval before its first PCM frame arrives.
     */
    final fun cancelIdleOrTerminalConnection() {
        val socket = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            val isTerminal = session.completed || session.cancelled || session.failure != null
            if (session.claimedByUtterance && !isTerminal) return@synchronized null
            cancelSessionLocked(session)
        }
        socket?.cancel()
    }

    /**
     * Performs ModelManager's delayed post-utterance cleanup. A successfully consumed final result
     * earns one replacement prewarm; an unused initial prewarm is still closed. The decision and
     * generation replacement are atomic so delayed cleanup cannot replace a rapid new mic press.
     */
    final fun cleanupAfterUtteranceOrIdle() {
        var socketToCancel: WebSocket? = null
        var sessionToOpen: Session? = null
        synchronized(lock) {
            val session = activeSession
            val isTerminal = session == null || session.completed || session.cancelled || session.failure != null
            if (session?.claimedByUtterance == true && !isTerminal) {
                prewarmAfterCleanupRequested = false
                return
            }

            if (prewarmAfterCleanupRequested) {
                val replacement = replaceActiveSessionLocked(claimedByUtterance = false)
                socketToCancel = replacement.oldSession?.socket
                sessionToOpen = replacement.newSession
            } else if (session != null) {
                socketToCancel = cancelSessionLocked(session)
            }
        }
        socketToCancel?.cancel()
        sessionToOpen?.let(::openSocket)
    }

    private fun startFreshSession(claimedByUtterance: Boolean) {
        val replacement = synchronized(lock) {
            replaceActiveSessionLocked(claimedByUtterance)
        }
        replacement.oldSession?.socket?.cancel()
        openSocket(replacement.newSession)
    }

    private fun replaceActiveSessionLocked(claimedByUtterance: Boolean): SessionReplacement {
        val oldSession = activeSession
        oldSession?.cancelled = true
        oldSession?.pending?.clear()
        oldSession?.queuedAudioBytes = 0
        oldSession?.heldAudio = null
        oldSession?.ready?.countDown()
        oldSession?.completion?.countDown()

        val newSession = Session(
            generation = generationCounter.incrementAndGet(),
            claimedByUtterance = claimedByUtterance,
        )
        activeSession = newSession
        prewarmAfterCleanupRequested = false
        lastFailure = null
        connectionState = RealtimeConnectionState.CONNECTING
        return SessionReplacement(oldSession, newSession)
    }

    private fun openSocket(session: Session) {
        try {
            val socket = client.newWebSocket(protocol.request(), listenerFor(session))
            synchronized(lock) {
                if (isActiveLocked(session) && !session.cancelled && session.failure == null) {
                    session.socket = socket
                } else {
                    socket.cancel()
                }
            }
        } catch (throwable: Throwable) {
            failSession(session, "Could not start ${protocol.providerName} realtime transcription", throwable)
        }
    }

    final override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
        if (buffer == null || nread <= 0) return false
        val sampleCount = nread.coerceAtMost(buffer.size)
        if (sampleCount <= 0) return false

        var session = activeSession
        if (session == null || session.finalRequested || session.completed || session.cancelled) {
            startFreshSession(claimedByUtterance = true)
            session = activeSession
        }
        checkNotNull(session)

        val pcm = shortsToLittleEndianBytes(buffer, sampleCount)
        synchronized(lock) {
            ensureWritable(session)
            session.claimedByUtterance = true
            session.hadAudio = true

            if (protocol.holdLastAudioChunkForCommit) {
                session.heldAudio?.let { enqueueOrSendLocked(session, Outbound.Audio(it, commit = false)) }
                session.heldAudio = pcm
            } else {
                enqueueOrSendLocked(session, Outbound.Audio(pcm, commit = false))
            }
            session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
        }

        // Manual endpointing commits only on release; no provider result should be inserted early.
        return false
    }

    final override fun getPartialResult(): String = synchronized(lock) {
        activeSession?.partialText.orEmpty()
    }

    final override fun getResult(): String = synchronized(lock) {
        activeSession?.finalText.orEmpty()
    }

    final override fun getFinalResult(): String {
        val session = activeSession ?: return ""
        synchronized(lock) {
            if (session.cancelled) return ""
            session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
            if (session.completed) return consumeFinalResultLocked(session)
            if (!session.hadAudio) {
                session.cancelled = true
                session.socket?.cancel()
                if (activeSession === session) connectionState = RealtimeConnectionState.CANCELLED
                return ""
            }

            if (!session.finalRequested) {
                session.finalRequested = true
                connectionState = RealtimeConnectionState.FINALIZING

                if (protocol.holdLastAudioChunkForCommit) {
                    session.heldAudio?.let { enqueueOrSendLocked(session, Outbound.Audio(it, commit = true)) }
                    session.heldAudio = null
                }
                protocol.finishMessages().forEach { enqueueOrSendLocked(session, Outbound.Json(it)) }
                session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
            }
        }

        if (!awaitUninterruptibly(session.completion, finalResultTimeoutMillis)) {
            val timeout = SocketTimeoutException(
                "${protocol.providerName} did not finalize within ${finalResultTimeoutMillis}ms"
            )
            failSession(session, timeout.message.orEmpty(), timeout)
            synchronized(lock) {
                // Prefer a final that won the exact deadline race over reporting a false timeout.
                if (session.completed && session.failure == null) {
                    return consumeFinalResultLocked(session)
                }
            }
            throw timeout.asRecognitionException(protocol.providerName)
        }

        synchronized(lock) {
            if (session.cancelled) return ""
            session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
            return consumeFinalResultLocked(session)
        }
    }

    private fun consumeFinalResultLocked(session: Session): String {
        if (isActiveLocked(session)) prewarmAfterCleanupRequested = true
        return removeSpaceForLocale(session.finalText.trim())
    }

    final override fun cancelCurrentUtterance() {
        val socket = synchronized(lock) {
            val session = activeSession ?: return@synchronized null
            cancelSessionLocked(session)
        }
        socket?.cancel()
    }

    private fun cancelSessionLocked(session: Session): WebSocket? {
        session.cancelled = true
        session.pending.clear()
        session.queuedAudioBytes = 0
        session.heldAudio = null
        session.partialText = ""
        session.finalText = ""
        session.ready.countDown()
        session.completion.countDown()
        if (activeSession === session) {
            activeSession = null
            connectionState = RealtimeConnectionState.CANCELLED
        }
        return session.socket
    }

    private fun listenerFor(session: Session): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!isActiveLocked(session) || session.failure != null) {
                    webSocket.cancel()
                    return
                }
                session.socket = webSocket
                session.transportOpen = true
                if (!protocol.waitForReadyEventBeforeAudio) {
                    markProviderReadyLocked(session)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(session, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleServerMessage(session, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (!isActiveLocked(session) || session.completed || session.cancelled) return
                failSessionLocked(
                    session,
                    RealtimeRecognitionException(
                        "${protocol.providerName} closed before returning a final result (code $code${reason.describeReason()})"
                    )
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val statusCode = response?.code
            response?.close()
            if (statusCode != null) protocol.onHandshakeHttpFailure(statusCode)
            val cause = if (statusCode == 401 || statusCode == 403) {
                RealtimeAuthenticationException(
                    "${protocol.providerName} sign-in expired",
                )
            } else {
                t
            }
            failSession(session, "${protocol.providerName} realtime connection failed", cause)
        }
    }

    private fun handleServerMessage(session: Session, message: String) {
        val event = runCatching { protocol.parseServerMessage(message) }.getOrElse {
            RealtimeServerEvent.Error("Could not parse the provider response", fatal = true)
        }
        handleServerEvent(session, event)
    }

    private fun handleServerEvent(session: Session, event: RealtimeServerEvent) {
        var socketToClose: WebSocket? = null
        synchronized(lock) {
            if (!isActiveLocked(session) || session.cancelled || session.failure != null) return
            when (event) {
                RealtimeServerEvent.Ready -> markProviderReadyLocked(session)
                is RealtimeServerEvent.Partial -> {
                    // Provider partials are replacement hypotheses, not append-only deltas.
                    session.partialText = removeSpaceForLocale(event.text.trim())
                }
                is RealtimeServerEvent.Final -> {
                    session.finalText = event.text
                    session.partialText = ""
                    session.completed = true
                    connectionState = RealtimeConnectionState.COMPLETED
                    session.completion.countDown()
                    socketToClose = session.socket
                }
                is RealtimeServerEvent.Error -> {
                    if (event.fatal) {
                        failSessionLocked(
                            session,
                            RealtimeRecognitionException("${protocol.providerName}: ${event.message}")
                        )
                        socketToClose = session.socket
                    }
                    // A provider explicitly marking a warning non-fatal means this session can
                    // continue. Do not count down completion or poison the next audio write.
                }
                RealtimeServerEvent.Ignored -> Unit
            }
        }
        socketToClose?.close(NORMAL_CLOSURE_CODE, "utterance complete")
    }

    private fun enqueueOrSendLocked(session: Session, outbound: Outbound) {
        if (!session.providerReady) {
            val addedBytes = (outbound as? Outbound.Audio)?.bytes?.size ?: 0
            if (session.queuedAudioBytes + addedBytes > maxPreOpenAudioBytes) {
                failSessionLocked(
                    session,
                    RealtimeRecognitionException(
                        "${protocol.providerName} connection was too slow; queued microphone audio exceeded " +
                            "${maxPreOpenAudioBytes / BYTES_PER_MILLISECOND}ms"
                    )
                )
                session.socket?.cancel()
                return
            }
            session.pending.addLast(outbound)
            session.queuedAudioBytes += addedBytes
            return
        }
        sendLocked(session, outbound)
    }

    private fun markProviderReadyLocked(session: Session) {
        if (!session.transportOpen || session.providerReady) return
        session.providerReady = true
        connectionState = if (session.finalRequested) {
            RealtimeConnectionState.FINALIZING
        } else {
            RealtimeConnectionState.OPEN
        }
        session.ready.countDown()
        flushPendingLocked(session)
    }

    private fun flushPendingLocked(session: Session) {
        while (session.pending.isNotEmpty() && session.failure == null) {
            val outbound = session.pending.removeFirst()
            if (outbound is Outbound.Audio) session.queuedAudioBytes -= outbound.bytes.size
            if (!sendLocked(session, outbound)) return
        }
        session.queuedAudioBytes = 0
    }

    private fun sendLocked(session: Session, outbound: Outbound): Boolean {
        val socket = session.socket ?: return false
        if (outbound is Outbound.Audio && !session.protocolStarted) {
            session.protocolStarted = true
            for (message in protocol.sessionStartMessages()) {
                if (!sendJsonLocked(session, socket, message)) return false
            }
        }
        if (outbound is Outbound.Audio) {
            protocol.binaryAudioMessage(outbound.bytes, outbound.commit)?.let { bytes ->
                return sendBinaryLocked(session, socket, bytes)
            }
        }
        return sendJsonLocked(
            session,
            socket,
            when (outbound) {
                is Outbound.Audio -> protocol.audioMessage(outbound.bytes, outbound.commit)
                is Outbound.Json -> outbound.value
            },
        )
    }

    private fun sendBinaryLocked(
        session: Session,
        socket: WebSocket,
        bytes: ByteArray,
    ): Boolean {
        if (socket.send(bytes.toByteString())) return true

        failSessionLocked(
            session,
            RealtimeRecognitionException("${protocol.providerName} rejected an outgoing audio frame")
        )
        socket.cancel()
        return false
    }

    private fun sendJsonLocked(session: Session, socket: WebSocket, message: String): Boolean {
        if (socket.send(message)) return true

        failSessionLocked(
            session,
            RealtimeRecognitionException("${protocol.providerName} rejected an outgoing audio message")
        )
        socket.cancel()
        return false
    }

    private fun ensureWritable(session: Session) {
        if (!isActiveLocked(session) || session.cancelled || session.finalRequested) {
            throw RealtimeRecognitionException("${protocol.providerName} session is no longer accepting audio")
        }
        session.failure?.let { throw it.asRecognitionException(protocol.providerName) }
    }

    private fun isActiveLocked(session: Session): Boolean =
        activeSession === session && session.generation == generationCounter.get()

    private fun failSession(session: Session, message: String, cause: Throwable) {
        var socket: WebSocket? = null
        synchronized(lock) {
            if (!isActiveLocked(session) || session.cancelled || session.completed) return
            val failure = if (cause is RealtimeRecognitionException) cause else RealtimeRecognitionException(message, cause)
            failSessionLocked(session, failure)
            socket = session.socket
        }
        socket?.cancel()
    }

    private fun failSessionLocked(session: Session, failure: Throwable) {
        if (session.failure != null || session.completed || session.cancelled) return
        session.failure = failure
        lastFailure = failure
        connectionState = RealtimeConnectionState.FAILED
        session.ready.countDown()
        session.completion.countDown()
    }

    private class Session(
        val generation: Long,
        var claimedByUtterance: Boolean,
    ) {
        val ready = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val pending = ArrayDeque<Outbound>()
        var socket: WebSocket? = null
        var transportOpen = false
        var providerReady = false
        var queuedAudioBytes = 0
        var heldAudio: ByteArray? = null
        var protocolStarted = false
        var hadAudio = false
        var finalRequested = false
        var completed = false
        var cancelled = false
        var partialText = ""
        var finalText = ""
        var failure: Throwable? = null
    }

    private data class SessionReplacement(
        val oldSession: Session?,
        val newSession: Session,
    )

    private sealed interface Outbound {
        data class Audio(val bytes: ByteArray, val commit: Boolean) : Outbound
        data class Json(val value: String) : Outbound
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val PCM_BYTES_PER_SAMPLE = 2
        const val BYTES_PER_MILLISECOND = SAMPLE_RATE_HZ * PCM_BYTES_PER_SAMPLE / 1_000
        const val NORMAL_CLOSURE_CODE = 1_000

        fun shortsToLittleEndianBytes(samples: ShortArray, sampleCount: Int): ByteArray {
            val result = ByteArray(sampleCount * PCM_BYTES_PER_SAMPLE)
            var outputIndex = 0
            for (inputIndex in 0 until sampleCount) {
                val value = samples[inputIndex].toInt()
                result[outputIndex++] = value.toByte()
                result[outputIndex++] = (value ushr 8).toByte()
            }
            return result
        }

        fun awaitUninterruptibly(latch: CountDownLatch, timeoutMillis: Long): Boolean {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var interrupted = false
            try {
                while (true) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) return latch.count == 0L
                    try {
                        return latch.await(remainingNanos, TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        fun Throwable.asRecognitionException(providerName: String): RealtimeRecognitionException =
            this as? RealtimeRecognitionException
                ?: RealtimeRecognitionException("$providerName realtime transcription failed", this)

        fun String.describeReason(): String = if (isBlank()) "" else ": $this"
    }
}
