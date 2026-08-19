package helium314.keyboard.voice

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.elishaazaria.sayboard.recognition.RecognitionListener
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MySpeechService @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    private val recognizer: Recognizer, sampleRate: Float,
    attributionContext: Context? = null
) {
    private val sampleRate: Int
    private val deliveryBufferSize: Int
    private val recorder: AudioRecord
    private val threadLock = Any()
    private var recognizerThread: RecognizerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        this.sampleRate = sampleRate.toInt()
        deliveryBufferSize = (this.sampleRate.toFloat() * DELIVERY_BUFFER_SECONDS)
            .roundToInt()
            .coerceAtLeast(1)
        val minimumRecorderBufferSize = AudioRecord.getMinBufferSize(
            this.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorderBufferSize = minimumRecorderBufferSize
            .coerceAtLeast(deliveryBufferSize * PCM_16_BIT_BYTES_PER_SAMPLE)
        recorder = AudioRecord.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
                setContext(attributionContext)
            }
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setAudioFormat(AudioFormat.Builder().apply {
                setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                setSampleRate(this@MySpeechService.sampleRate)
                setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            }.build())
            setBufferSizeInBytes(recorderBufferSize)
        }.build()

        if (recorder.state == 0) {
            recorder.release()
            throw IOException("Failed to initialize recorder. Microphone might be already in use.")
        }
    }

    fun startListening(listener: RecognitionListener): Boolean {
        synchronized(threadLock) {
            if (recognizerThread != null) return false
            return RecognizerThread(listener).let { thread ->
                recognizerThread = thread
                thread.start()
                true
            }
        }
    }

    var recordDevice: AudioDeviceInfo?
        get() = recorder.routedDevice
        set(value) {
            recorder.preferredDevice = value
        }

    /** Requests finalization without blocking the caller. */
    fun requestFinish(): Boolean = requestRecognizerThreadStop(StopMode.FINISH)

    /** Requests cancellation without blocking the caller. Cancellation never finalizes audio. */
    fun requestCancel(): Boolean = requestRecognizerThreadStop(StopMode.CANCEL)

    private fun requestRecognizerThreadStop(mode: StopMode): Boolean {
        val thread = synchronized(threadLock) { recognizerThread } ?: return false
        thread.requestStop(mode)
        return true
    }

    /** Waits for a previously requested finish/cancel and releases the thread reference. */
    fun awaitStopped(): Boolean {
        val thread = synchronized(threadLock) { recognizerThread } ?: return false
        try {
            thread.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(threadLock) {
            if (recognizerThread === thread) recognizerThread = null
        }
        return true
    }

    /** Backwards-compatible finalizing stop. */
    fun stop(): Boolean {
        val requested = requestFinish()
        if (requested) awaitStopped()
        return requested
    }

    /** Cancels capture and discards buffered audio without asking the recognizer for a result. */
    fun cancel(): Boolean {
        val requested = requestCancel()
        if (requested) awaitStopped()
        return requested
    }

    fun shutdown() {
        recorder.release()
    }

    fun setPause(paused: Boolean) {
        synchronized(threadLock) { recognizerThread }?.setPause(paused)
    }

    private fun stopRecorderSafely() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
        }
    }

    private inner class RecognizerThread @JvmOverloads constructor(
        var listener: RecognitionListener,
        timeout: Int = -1
    ) : Thread() {
        private var remainingSamples: Int
        private val timeoutSamples: Int

        @Volatile
        private var paused = false

        init {
            if (timeout != -1) {
                timeoutSamples = timeout * sampleRate / 1000
            } else {
                timeoutSamples = -1
            }
            remainingSamples = timeoutSamples
        }

        fun setPause(paused: Boolean) {
            this.paused = paused
        }

        override fun run() {
            try {
                // Every utterance starts from an empty recognizer session. This is especially
                // important after cancellation, where getFinalResult() is deliberately skipped.
                if (!resetRecognizerUnlessCancelled(recognizer, finalizationGate)) return
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Failed to start recording. Microphone might be already in use.")
                }

                val buffer = ShortArray(deliveryBufferSize)
                var lastPartialResult = ""
                while (!isInterrupted && (timeoutSamples == -1 || remainingSamples > 0)) {
                    val nread = recorder.read(buffer, 0, buffer.size)
                    if (!paused) {
                        if (nread < 0) {
                            throw RuntimeException("error reading audio buffer")
                        }
                        if (recognizer.acceptWaveForm(buffer, nread)) {
                            val result = recognizer.getResult()
                            mainHandler.post { listener.onResult(result) }
                        } else {
                            val partial = recognizer.getPartialResult()
                            if (partial != lastPartialResult) {
                                lastPartialResult = partial
                                mainHandler.post { listener.onPartialResult(partial) }
                            }
                        }
                        if (timeoutSamples != -1) {
                            remainingSamples -= nread
                        }
                    }
                }

                stopRecorderSafely()

                val finalResult = finalResultUnlessCancelled(
                    recognizer,
                    finalizationGate,
                    ::cancelRecognizerOnce
                )
                    ?: return
                if (timeoutSamples != -1 && remainingSamples <= 0) {
                    mainHandler.post { listener.onTimeout() }
                } else {
                    mainHandler.post { listener.onFinalResult(finalResult) }
                }
            } catch (e: Exception) {
                stopRecorderSafely()
                if (!isCancellationRequested()) {
                    mainHandler.post { listener.onError(e) }
                } else {
                    cancelRecognizerOnce()
                }
            }
        }

        private val finalizationGate = FinalizationGate()
        private val recognizerCancelled = AtomicBoolean(false)

        fun requestStop(mode: StopMode) {
            if (mode == StopMode.CANCEL) {
                finalizationGate.requestCancel()
                // Close streaming sockets/queued uploads immediately. The atomic guard also makes
                // the capture-thread cleanup path safe for provider implementations that expect a
                // single cancellation callback per utterance.
                cancelRecognizerOnce()
            }
            interrupt()
        }

        private fun isCancellationRequested(): Boolean =
            finalizationGate.isCancellationRequested()

        private fun cancelRecognizerOnce() {
            if (!recognizerCancelled.compareAndSet(false, true)) return
            runCatching { recognizer.cancel() }
        }
    }

    companion object {
        private const val DELIVERY_BUFFER_SECONDS = 0.04f
        private const val PCM_16_BIT_BYTES_PER_SAMPLE = 2
    }

    private enum class StopMode {
        FINISH, CANCEL
    }
}

/**
 * Thread-safe boundary between capture termination and provider finalization.
 * A cancellation observed before [beginFinalization] guarantees that the provider is never asked
 * for a final result. A later cancellation still suppresses delivery of an in-flight result.
 */
internal class FinalizationGate {
    private val lock = Any()

    @Volatile
    private var cancellationRequested = false

    fun requestCancel() {
        synchronized(lock) {
            cancellationRequested = true
        }
    }

    fun beginFinalization(): Boolean = synchronized(lock) {
        if (cancellationRequested) return@synchronized false
        true
    }

    fun isCancellationRequested(): Boolean = cancellationRequested
}

internal fun finalResultUnlessCancelled(
    recognizer: Recognizer,
    gate: FinalizationGate,
    cancelRecognizer: () -> Unit = recognizer::cancel
): String? {
    if (!gate.beginFinalization()) {
        cancelRecognizer()
        return null
    }
    val result = recognizer.getFinalResult()
    return if (gate.isCancellationRequested()) {
        // The provider call may already have started, but a canceled session must never publish.
        cancelRecognizer()
        null
    } else {
        result
    }
}

/**
 * Starts a recognizer generation without allowing a rapid cancel to strand a generation opened
 * by reset. The second cancellation is intentional: reset may have raced the caller's first one.
 */
internal fun resetRecognizerUnlessCancelled(
    recognizer: Recognizer,
    gate: FinalizationGate,
): Boolean {
    if (gate.isCancellationRequested()) return false
    recognizer.reset()
    if (!gate.isCancellationRequested()) return true
    runCatching { recognizer.cancel() }
    return false
}
