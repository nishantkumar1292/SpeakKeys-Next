// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoskRecognizerAdapterTest {
    @Test
    fun convertsVoskJsonToPlainRecognitionText() {
        val backend = FakeBackend(
            resultJson = """{"text":" नमस्ते दुनिया "}""",
            partialJson = """{"partial":" hello wor "}""",
            finalJson = """{"text":"final words","result":[]}""",
        )
        val adapter = VoskRecognizerAdapter(backend, "hi")

        assertEquals("नमस्ते दुनिया", adapter.getResult())
        assertEquals("hello wor", adapter.getPartialResult())
        assertEquals("final words", adapter.getFinalResult())
        assertEquals(16_000f, adapter.sampleRate)
        assertEquals("hi", adapter.languageCode)
    }

    @Test
    fun feedsOnlyValidPcmLengthsToVosk() {
        val backend = FakeBackend(acceptResult = true)
        val adapter = VoskRecognizerAdapter(backend, "en")
        val pcm = shortArrayOf(1, 2, 3)

        assertFalse(adapter.acceptWaveForm(null, 3))
        assertFalse(adapter.acceptWaveForm(pcm, 0))
        assertTrue(adapter.acceptWaveForm(pcm, 2))
        assertEquals(2, backend.lastAcceptedLength)
        assertFailsWith<IllegalArgumentException> { adapter.acceptWaveForm(pcm, 4) }
    }

    @Test
    fun malformedNativeJsonFailsInsteadOfCommittingJsonToTheEditor() {
        val adapter = VoskRecognizerAdapter(
            FakeBackend(finalJson = "not-json"),
            "hi",
        )

        assertFailsWith<IllegalStateException> { adapter.getFinalResult() }
    }

    @Test
    fun closeIsIdempotentAndPreventsNativeReuse() {
        val backend = FakeBackend()
        val adapter = VoskRecognizerAdapter(backend, "hi")

        adapter.close()
        adapter.close()

        assertEquals(1, backend.closeCount)
        assertFailsWith<IllegalStateException> { adapter.reset() }
    }

    private class FakeBackend(
        private val resultJson: String = """{"text":""}""",
        private val partialJson: String = """{"partial":""}""",
        private val finalJson: String = """{"text":""}""",
        private val acceptResult: Boolean = false,
    ) : VoskRecognizerBackend {
        var lastAcceptedLength: Int? = null
        var closeCount = 0

        override fun reset() = Unit

        override fun acceptWaveForm(buffer: ShortArray, length: Int): Boolean {
            lastAcceptedLength = length
            return acceptResult
        }

        override fun result(): String = resultJson
        override fun partialResult(): String = partialJson
        override fun finalResult(): String = finalJson
        override fun close() {
            closeCount++
        }
    }
}
