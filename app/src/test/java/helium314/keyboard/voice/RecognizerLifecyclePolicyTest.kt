package helium314.keyboard.voice

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.recognition.recognizers.RecoverableAuthFailureSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerLifecyclePolicyTest {

    @Test
    fun closedInitializationFailureBecomesATerminalStartFailure() {
        val source = FakeSource(
            closed = true,
            state = RecognizerState.ERROR,
            errorMessage = "Downloaded model is damaged",
        )

        val error = recognizerInitializationFailure(source)

        assertTrue(error is RecognizerInitializationException)
        assertEquals("Downloaded model is damaged", error?.message)
    }

    @Test
    fun readyInitializedSourceCanStart() {
        val source = FakeSource(closed = false, state = RecognizerState.READY)

        assertNull(recognizerInitializationFailure(source))
    }

    @Test
    fun onlyExplicitRecoverableAuthFailureIsRetried() {
        val genericFailure = FakeSource(closed = true, state = RecognizerState.ERROR)
        val recoverableAuthFailure = RecoverableSource(
            genericFailure,
            hasRecoverableAuthFailure = true,
        )
        val resolvedAuthFailure = RecoverableSource(
            genericFailure,
            hasRecoverableAuthFailure = false,
        )

        assertFalse(shouldRetryRecognizerInitialization(genericFailure))
        assertTrue(shouldRetryRecognizerInitialization(recoverableAuthFailure))
        assertFalse(shouldRetryRecognizerInitialization(resolvedAuthFailure))
    }

    private class RecoverableSource(
        delegate: RecognizerSource,
        override val hasRecoverableAuthFailure: Boolean,
    ) : RecognizerSource by delegate, RecoverableAuthFailureSource

    private class FakeSource(
        override val closed: Boolean,
        state: RecognizerState,
        override val errorMessage: String = "Unavailable",
    ) : RecognizerSource {
        override suspend fun initialize() = Unit
        override val recognizer: Recognizer
            get() = error("Not used")
        override fun close(freeRAM: Boolean) = Unit
        override val stateFlow: StateFlow<RecognizerState> = MutableStateFlow(state)
        override val addSpaces: Boolean = true
        override val name: String = "Test"
        override val locale: SpeakKeysLocale = SpeakKeysLocale.ROOT
    }
}
