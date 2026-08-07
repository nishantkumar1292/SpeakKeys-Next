package helium314.keyboard.voice

import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MySpeechServiceTest {

    @Test
    fun cancelBeforeFinalizationNeverRequestsProviderResult() {
        val recognizer = CountingRecognizer()
        val gate = FinalizationGate().apply { requestCancel() }

        val result = finalResultUnlessCancelled(recognizer, gate)

        assertNull(result)
        assertEquals(0, recognizer.finalResultCalls)
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(0, recognizer.resetCalls)
    }

    @Test
    fun normalFinishRequestsAndReturnsProviderResult() {
        val recognizer = CountingRecognizer(finalResult = "hello")
        val gate = FinalizationGate()

        val result = finalResultUnlessCancelled(recognizer, gate)

        assertEquals("hello", result)
        assertEquals(1, recognizer.finalResultCalls)
        assertEquals(0, recognizer.cancelCalls)
        assertEquals(0, recognizer.resetCalls)
    }

    @Test
    fun cancellationDuringProviderCallSuppressesStaleResult() {
        val gate = FinalizationGate()
        val recognizer = CountingRecognizer(
            finalResult = "stale",
            onFinalResult = gate::requestCancel
        )

        val result = finalResultUnlessCancelled(recognizer, gate)

        assertNull(result)
        assertEquals(1, recognizer.finalResultCalls)
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(0, recognizer.resetCalls)
    }

    @Test
    fun cancellationThatRacesResetClosesTheNewRecognizerGeneration() {
        val gate = FinalizationGate()
        val recognizer = CountingRecognizer(onReset = gate::requestCancel)

        val started = resetRecognizerUnlessCancelled(recognizer, gate)

        assertEquals(false, started)
        assertEquals(1, recognizer.resetCalls)
        assertEquals(1, recognizer.cancelCalls)
        assertEquals(0, recognizer.finalResultCalls)
    }

    private class CountingRecognizer(
        private val finalResult: String = "",
        private val onFinalResult: () -> Unit = {},
        private val onReset: () -> Unit = {},
    ) : Recognizer {
        var resetCalls = 0
            private set
        var finalResultCalls = 0
            private set
        var cancelCalls = 0
            private set

        override fun reset() {
            resetCalls++
            onReset()
        }

        override fun cancel() {
            cancelCalls++
        }

        override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean = false

        override fun getResult(): String = ""

        override fun getPartialResult(): String = ""

        override fun getFinalResult(): String {
            finalResultCalls++
            onFinalResult()
            return finalResult
        }

        override val sampleRate: Float = 16_000f

        override val languageCode: String? = "en"
    }
}
