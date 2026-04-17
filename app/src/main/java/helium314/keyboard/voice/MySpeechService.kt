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
import kotlin.math.roundToInt

class MySpeechService @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    private val recognizer: Recognizer, sampleRate: Float,
    attributionContext: Context? = null
) {
    private val sampleRate: Int
    private val bufferSize: Int
    private val recorder: AudioRecord
    private var recognizerThread: RecognizerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        this.sampleRate = sampleRate.toInt()
        bufferSize = (this.sampleRate.toFloat() * BUFFER_SIZE_SECONDS).roundToInt()
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
            setBufferSizeInBytes(bufferSize * 2)
        }.build()

        if (recorder.state == 0) {
            recorder.release()
            throw IOException("Failed to initialize recorder. Microphone might be already in use.")
        }
    }

    fun startListening(listener: RecognitionListener): Boolean {
        return if (null != recognizerThread) {
            false
        } else {
            recognizerThread = RecognizerThread(listener)
            recognizerThread!!.start()
            true
        }
    }

    var recordDevice: AudioDeviceInfo?
        get() = recorder.routedDevice
        set(value) {
            recorder.preferredDevice = value
        }

    private fun stopRecognizerThread(): Boolean {
        return if (null == recognizerThread) {
            false
        } else {
            try {
                recognizerThread!!.interrupt()
                recognizerThread!!.join()
            } catch (var2: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            recognizerThread = null
            true
        }
    }

    fun stop(): Boolean {
        return stopRecognizerThread()
    }

    fun cancel(): Boolean {
        if (recognizerThread != null) {
            recognizerThread!!.setPause(true)
        }
        return stopRecognizerThread()
    }

    fun shutdown() {
        recorder.release()
    }

    fun setPause(paused: Boolean) {
        if (recognizerThread != null) {
            recognizerThread!!.setPause(paused)
        }
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

        @Volatile
        private var reset = false

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
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Failed to start recording. Microphone might be already in use.")
                }

                val buffer = ShortArray(bufferSize)
                while (!interrupted() && (timeoutSamples == -1 || remainingSamples > 0)) {
                    val nread = recorder.read(buffer, 0, buffer.size)
                    if (!paused) {
                        if (reset) {
                            recognizer.reset()
                            reset = false
                        }
                        if (nread < 0) {
                            throw RuntimeException("error reading audio buffer")
                        }
                        var result: String?
                        if (recognizer.acceptWaveForm(buffer, nread)) {
                            result = recognizer.getResult()
                            mainHandler.post { listener.onResult(result) }
                        } else {
                            result = recognizer.getPartialResult()
                            mainHandler.post { listener.onPartialResult(result) }
                        }
                        if (timeoutSamples != -1) {
                            remainingSamples -= nread
                        }
                    }
                }

                stopRecorderSafely()

                val finalResult = recognizer.getFinalResult()
                if (!paused) {
                    if (timeoutSamples != -1 && remainingSamples <= 0) {
                        mainHandler.post { listener.onTimeout() }
                    } else {
                        mainHandler.post { listener.onFinalResult(finalResult) }
                    }
                } else if (finalResult.isNotEmpty()) {
                    mainHandler.post { listener.onFinalResult(finalResult) }
                }
            } catch (e: Exception) {
                stopRecorderSafely()
                mainHandler.post { listener.onError(e) }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE_SECONDS = 0.2f
    }
}
