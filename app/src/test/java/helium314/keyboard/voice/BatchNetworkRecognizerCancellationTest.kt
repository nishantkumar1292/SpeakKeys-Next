package helium314.keyboard.voice

import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.sources.ProxiedCloudRecognizer
import com.elishaazaria.sayboard.recognition.recognizers.sources.SarvamCloudRecognizer
import com.elishaazaria.sayboard.recognition.recognizers.sources.WhisperCloudRecognizer
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.recognition.auth.AuthTokenProvider
import com.elishaazaria.sayboard.recognition.preferences.PreferencesRepository
import com.elishaazaria.sayboard.recognition.recognizers.providers.ProxiedCloudProvider
import com.elishaazaria.sayboard.recognition.recognizers.sources.ProxiedCloud
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.mockito.Mockito

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatchNetworkRecognizerCancellationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun cancelBeforeFinalizationSendsNoAudioForAnyBatchProvider() {
        val recognizers = listOf<Recognizer>(
            WhisperCloudRecognizer(
                apiKey = "openai-key",
                languageCode = "en",
                endpoint = server.url("/openai").toString(),
            ),
            SarvamCloudRecognizer(
                apiKey = "sarvam-key",
                languageCode = "hi",
                endpoint = server.url("/sarvam").toString(),
            ),
            ProxiedCloudRecognizer(
                tokenProvider = { "firebase-token" },
                provider = "sarvam",
                languageCode = "hi",
                proxyBaseUrl = server.url("/").toString(),
            ),
        )

        try {
            recognizers.forEach { recognizer ->
                feedAudio(recognizer)
                recognizer.cancel()
                assertEquals("", recognizer.getFinalResult())
            }
            assertEquals(0, server.requestCount)
        } finally {
            recognizers.forEach(::closeRecognizer)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun cancelDuringWhisperRequestAbortsWaitAndSuppressesResult() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val recognizer = WhisperCloudRecognizer(
            apiKey = "openai-key",
            languageCode = "en",
            endpoint = server.url("/openai").toString(),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            feedAudio(recognizer)
            val result = executor.submit<String> { recognizer.getFinalResult() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            recognizer.cancel()

            assertEquals("", result.get(2, TimeUnit.SECONDS))
        } finally {
            recognizer.close()
            executor.shutdownNow()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun closeDuringSarvamRequestCancelsTheInFlightClientCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val recognizer = SarvamCloudRecognizer(
            apiKey = "sarvam-key",
            languageCode = "hi",
            endpoint = server.url("/sarvam").toString(),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            feedAudio(recognizer)
            val result = executor.submit<String> { recognizer.getFinalResult() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            recognizer.close()

            assertEquals("", result.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelDuringProxyRetryDelayPreventsAnotherAttempt() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"temporary\"}"))
        val recognizer = ProxiedCloudRecognizer(
            tokenProvider = { "firebase-token" },
            provider = "sarvam",
            languageCode = "hi",
            proxyBaseUrl = server.url("/").toString(),
            maxRetries = 2,
            retryDelayMs = 300,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            feedAudio(recognizer)
            val result = executor.submit<String> { recognizer.getFinalResult() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            Thread.sleep(50)

            recognizer.cancel()

            assertEquals("", result.get(2, TimeUnit.SECONDS))
            Thread.sleep(400)
            assertEquals(1, server.requestCount)
        } finally {
            recognizer.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun proxyDoesNotRetryDeterministicClientOrAuthenticationErrors() {
        for (status in listOf(400, 401, 403, 404)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"error\":\"invalid request\"}"))
            val tokenCalls = AtomicInteger()
            val requestsBefore = server.requestCount
            val recognizer = ProxiedCloudRecognizer(
                tokenProvider = {
                    tokenCalls.incrementAndGet()
                    "firebase-token"
                },
                provider = "sarvam",
                languageCode = "hi",
                proxyBaseUrl = server.url("/").toString(),
                maxRetries = 4,
                retryDelayMs = 10,
            )
            try {
                feedAudio(recognizer)
                assertThrows(Exception::class.java) { recognizer.getFinalResult() }
                assertEquals(requestsBefore + 1, server.requestCount)
                assertEquals(1, tokenCalls.get())
            } finally {
                recognizer.close()
            }
        }
    }

    @Test
    fun proxyDefaultPolicyRetriesATransientFailureOnlyOnceAndQuickly() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"temporary\"}"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"temporary\"}"))
        val tokenCalls = AtomicInteger()
        val recognizer = ProxiedCloudRecognizer(
            tokenProvider = {
                tokenCalls.incrementAndGet()
                "firebase-token"
            },
            provider = "sarvam",
            languageCode = "hi",
            proxyBaseUrl = server.url("/").toString(),
        )
        try {
            feedAudio(recognizer)
            val startedAt = System.nanoTime()
            assertThrows(Exception::class.java) { recognizer.getFinalResult() }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertEquals(2, server.requestCount)
            assertEquals(2, tokenCalls.get())
            assert(elapsedMillis < 1_500) { "bounded proxy retry took ${elapsedMillis}ms" }
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun emptyProxyTokenFailsWithoutNetworkOrRetryDelay() {
        val tokenCalls = AtomicInteger()
        val recognizer = ProxiedCloudRecognizer(
            tokenProvider = {
                tokenCalls.incrementAndGet()
                null
            },
            provider = "sarvam",
            languageCode = "hi",
            proxyBaseUrl = server.url("/").toString(),
            maxRetries = 4,
            retryDelayMs = 500,
        )
        try {
            feedAudio(recognizer)
            val startedAt = System.nanoTime()
            assertThrows(Exception::class.java) { recognizer.getFinalResult() }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertEquals(1, tokenCalls.get())
            assertEquals(0, server.requestCount)
            assert(elapsedMillis < 250) { "missing auth token took ${elapsedMillis}ms" }
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun proxiedSarvamMixedOutputUsesCodemixMode() {
        val prefs = Mockito.mock(PreferencesRepository::class.java)
        Mockito.`when`(prefs.getVoiceOutputStyle()).thenReturn("mixed")
        Mockito.`when`(prefs.getSarvamLanguage()).thenReturn("unknown")
        val auth = Mockito.mock(AuthTokenProvider::class.java)
        Mockito.`when`(auth.isSignedIn).thenReturn(true)
        val provider = ProxiedCloudProvider(prefs, auth)
        val model = InstalledModelReference(
            path = "proxied://sarvam",
            name = "Sarvam Cloud (Proxied)",
            type = ModelType.ProxiedSarvamCloud,
        )

        val source = provider.recognizerSourceForModel(model) as ProxiedCloud
        val paramsField = ProxiedCloud::class.java.getDeclaredField("providerParams")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val params = paramsField.get(source) as Map<String, String>

        assertEquals("codemix", params["mode"])
    }

    @Suppress("DEPRECATION")
    @Test
    fun resetCancelsOldGenerationAndOnlyReturnsTheNewTranscript() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"new transcript\"}"))
        val recognizer = WhisperCloudRecognizer(
            apiKey = "openai-key",
            languageCode = "en",
            endpoint = server.url("/openai").toString(),
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            feedAudio(recognizer)
            val staleResult = executor.submit<String> { recognizer.getFinalResult() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            feedAudio(recognizer)

            assertEquals("new transcript", recognizer.getFinalResult())
            assertEquals("", staleResult.get(2, TimeUnit.SECONDS))
            assertEquals(2, server.requestCount)
        } finally {
            recognizer.close()
            executor.shutdownNow()
        }
    }

    private fun feedAudio(recognizer: Recognizer) {
        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, -2, 3, -4), 4)
    }

    private fun closeRecognizer(recognizer: Recognizer) {
        when (recognizer) {
            is WhisperCloudRecognizer -> recognizer.close()
            is SarvamCloudRecognizer -> recognizer.close()
            is ProxiedCloudRecognizer -> recognizer.close()
        }
    }
}
