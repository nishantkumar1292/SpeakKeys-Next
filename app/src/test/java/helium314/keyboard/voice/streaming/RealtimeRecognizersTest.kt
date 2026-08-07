package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

class RealtimeRecognizersTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test
    fun sarvamStreamsPcmAndFinalizesWithManualSpeechEnd() {
        assertEquals(
            "wss://api.sarvam.ai/speech-to-text-realtime/ws",
            SarvamRealtimeRecognizer.SARVAM_REALTIME_ENDPOINT,
        )
        val receivedMessages = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"test\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedMessages += text
                when (Json.parseToJsonElement(text).jsonObject["event"]?.jsonPrimitive?.content) {
                    "audio_input" -> webSocket.send(
                        "{\"event\":\"transcript.partial\",\"utterance_idx\":0,\"text\":\"namaste\"}"
                    )
                    "speech_end" -> webSocket.send(
                        "{\"event\":\"transcript.final\",\"utterance_idx\":0,\"text\":\"namaste duniya\"}"
                    )
                }
            }
        }))
        server.start()

        val recognizer = SarvamRealtimeRecognizer(
            apiKey = "sarvam-test-key",
            languageCode = "hi",
            sarvamLanguageCode = "hi-IN",
            prompt = "SpeakKeys",
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )

        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, -2, 3, -4), 4)

        assertTrue(waitUntil { recognizer.getPartialResult() == "namaste" })
        assertEquals("namaste duniya", recognizer.getFinalResult())
        assertEquals(RealtimeConnectionState.COMPLETED, recognizer.connectionState)

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/realtime", request.requestUrl?.encodedPath)
        assertEquals("sarvam-test-key", request.getHeader("API-SUBSCRIPTION-KEY"))
        assertEquals("hi-IN", request.requestUrl?.queryParameter("language_code"))
        assertEquals("saaras:v3-realtime", request.requestUrl?.queryParameter("model"))
        assertEquals("manual", request.requestUrl?.queryParameter("endpointing"))
        assertEquals("codemix", request.requestUrl?.queryParameter("mode"))
        assertEquals("balanced", request.requestUrl?.queryParameter("stream_type"))
        assertEquals("linear16", request.requestUrl?.queryParameter("encoding"))
        assertEquals("16000", request.requestUrl?.queryParameter("sample_rate"))
        assertEquals("SpeakKeys", request.requestUrl?.queryParameter("prompt"))
        val clientEvents = receivedMessages.map {
            Json.parseToJsonElement(it).jsonObject["event"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("speech_start", "audio_input", "speech_end"), clientEvents)

        val audioMessage = receivedMessages
            .map { Json.parseToJsonElement(it).jsonObject }
            .first { it["event"]?.jsonPrimitive?.content == "audio_input" }
        assertEquals(
            listOf<Byte>(1, 0, -2, -1, 3, 0, -4, -1),
            Base64.getDecoder().decode(audioMessage["audio"]!!.jsonPrimitive.content).toList(),
        )
    }

    @Test
    fun sarvamHonorsTheDocumentedNonFatalErrorFlag() {
        val protocol = SarvamRealtimeProtocol(
            apiKey = "sarvam-test-key",
            languageCode = "auto",
            mode = "codemix",
            streamType = "balanced",
            prompt = "",
            endpoint = "wss://example.invalid/realtime",
        )

        assertEquals(
            RealtimeServerEvent.Error("temporary warning", fatal = false),
            protocol.parseServerMessage(
                "{\"event\":\"error\",\"code\":\"slow_down\",\"message\":\"temporary warning\"," +
                    "\"is_fatal\":false}"
            ),
        )
    }

    @Test
    fun sarvamNonFatalWarningDoesNotPoisonTheUtterance() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"test\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (Json.parseToJsonElement(text).jsonObject["event"]?.jsonPrimitive?.content) {
                    "audio_input" -> {
                        webSocket.send(
                            "{\"event\":\"error\",\"code\":\"slow_down\",\"message\":\"temporary warning\"," +
                                "\"is_fatal\":false}"
                        )
                        webSocket.send(
                            "{\"event\":\"transcript.partial\",\"utterance_idx\":0,\"text\":\"namaste\"}"
                        )
                    }
                    "speech_end" -> webSocket.send(
                        "{\"event\":\"transcript.final\",\"utterance_idx\":0,\"text\":\"namaste duniya\"}"
                    )
                }
            }
        }))
        server.start()

        val recognizer = SarvamRealtimeRecognizer(
            apiKey = "sarvam-test-key",
            languageCode = "hi",
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )

        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)

        assertTrue(waitUntil { recognizer.getPartialResult() == "namaste" })
        assertEquals("namaste duniya", recognizer.getFinalResult())
        assertEquals(RealtimeConnectionState.COMPLETED, recognizer.connectionState)
    }

    @Test
    fun sourcePrewarmsSocketButDoesNotStartSpeechBeforePcmArrives() {
        val receivedMessages = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"test\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedMessages += text
            }
        }))
        server.start()

        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
        )

        runBlocking { source.initialize() }
        assertTrue(waitUntil { server.requestCount == 1 })
        assertTrue(receivedMessages.isEmpty())

        // MySpeechService's reset reuses the pristine prepared session rather than reconnecting.
        source.recognizer.reset()
        source.recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        assertTrue(waitUntil { receivedMessages.size == 2 })
        assertEquals(1, server.requestCount)
        assertTrue(receivedMessages[0].contains("\"event\":\"speech_start\""))
        assertTrue(receivedMessages[1].contains("\"event\":\"audio_input\""))

        source.close(freeRAM = true)
    }

    @Test
    fun nonFreeingCloseDisposesAnUnclaimedPrewarm() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"idle\"}")
            }
        }))
        server.start()

        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
        )

        runBlocking { source.initialize() }
        val recognizer = source.recognizer as SarvamRealtimeRecognizer
        assertTrue(waitUntil { server.requestCount == 1 })

        source.close(freeRAM = false)

        assertFalse(source.closed)
        assertEquals(RealtimeConnectionState.CANCELLED, recognizer.connectionState)
        source.close(freeRAM = true)
    }

    @Test
    fun delayedSourceCloseCannotCancelANewerClaimedUtterance() {
        fun responseFor(finalText: String) = MockResponse().withWebSocketUpgrade(
            object : ClosingWebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"test\"}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = Json.parseToJsonElement(text).jsonObject["event"]
                        ?.jsonPrimitive?.content
                    if (event == "speech_end") {
                        webSocket.send(
                            "{\"event\":\"transcript.final\",\"utterance_idx\":0,\"text\":\"$finalText\"}"
                        )
                    }
                }
            },
        )
        server.enqueue(responseFor("first"))
        server.enqueue(responseFor("second"))
        server.start()

        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )
        runBlocking { source.initialize() }
        val recognizer = source.recognizer

        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        assertEquals("first", recognizer.getFinalResult())

        // ModelManager queues close(false) after publishing UI-idle. Model the rapid next tap by
        // claiming its new generation before that delayed cleanup reaches the source.
        recognizer.reset()
        source.close(freeRAM = false)
        recognizer.acceptWaveForm(shortArrayOf(3, 4), 2)

        assertEquals("second", recognizer.getFinalResult())
        assertEquals(2, server.requestCount)
        source.close(freeRAM = true)
    }

    @Test
    fun completedUtterancePrewarmsTheNextSocketWithoutAnExtraMicStartConnection() {
        fun responseFor(finalText: String) = MockResponse().withWebSocketUpgrade(
            object : ClosingWebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"event\":\"session.begin\",\"request_id\":\"test\"}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = Json.parseToJsonElement(text).jsonObject["event"]
                        ?.jsonPrimitive?.content
                    if (event == "speech_end") {
                        webSocket.send(
                            "{\"event\":\"transcript.final\",\"utterance_idx\":0,\"text\":\"$finalText\"}"
                        )
                    }
                }
            },
        )
        server.enqueue(responseFor("first"))
        server.enqueue(responseFor("second"))
        server.start()

        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )
        runBlocking { source.initialize() }

        source.recognizer.reset()
        source.recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        assertEquals("first", source.recognizer.getFinalResult())

        // This is the normal delayed ModelManager cleanup after delivery of the final callback.
        source.close(freeRAM = false)
        assertTrue(waitUntil { server.requestCount == 2 })

        source.recognizer.reset()
        source.recognizer.acceptWaveForm(shortArrayOf(3, 4), 2)
        assertEquals("second", source.recognizer.getFinalResult())
        assertEquals(2, server.requestCount)
        source.close(freeRAM = true)
    }

    @Test
    fun sourceInitializationTimesOutUnlessTheProviderPublishesReady() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {}))
        server.start()

        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
            handshakeTimeoutMillis = 80,
        )

        val startedAt = System.nanoTime()
        runBlocking { source.initialize() }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(RecognizerState.ERROR, source.stateFlow.value)
        assertTrue(source.closed)
        assertTrue(source.errorMessage.contains("did not become ready"))
        assertTrue(elapsedMillis >= 60)
        assertTrue(elapsedMillis < 1_000)
    }

    @Test
    fun rejectedRealtimeAuthenticationNeverPublishesReady() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.start()

        val source = ElevenLabsRealtimeRecognizerSource(
            auth = ElevenLabsRealtimeAuth.ApiKey("invalid-key"),
            displayLocale = SpeakKeysLocale("en", "IN"),
            client = client,
            endpoint = server.url("/realtime").toString(),
            handshakeTimeoutMillis = 500,
        )

        runBlocking { source.initialize() }

        assertEquals(RecognizerState.ERROR, source.stateFlow.value)
        assertTrue(source.closed)
    }

    @Test
    fun invalidSarvamRealtimeConfigurationNeverOpensASocketOrPublishesReady() {
        val source = SarvamRealtimeRecognizerSource(
            apiKey = "sarvam-test-key",
            displayLocale = SpeakKeysLocale("en", "IN"),
            sarvamLanguageCode = "xx-XX",
            client = client,
            endpoint = "wss://example.invalid/realtime",
        )

        runBlocking { source.initialize() }

        assertEquals(RecognizerState.ERROR, source.stateFlow.value)
        assertTrue(source.closed)
        assertTrue(source.errorMessage.contains("Unsupported Sarvam realtime language code"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun elevenLabsReplacesPartialsAndCommitsTheLastRealAudioChunk() {
        val receivedAudioMessages = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"message_type\":\"session_started\",\"session_id\":\"test\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = Json.parseToJsonElement(text).jsonObject
                if (json["message_type"]?.jsonPrimitive?.content != "input_audio_chunk") return
                receivedAudioMessages += text
                val commit = json["commit"]?.jsonPrimitive?.booleanOrNull == true
                if (commit) {
                    webSocket.send("{\"message_type\":\"final_transcript\",\"text\":\"hello duni\"}")
                    webSocket.send("{\"message_type\":\"committed_transcript\",\"text\":\"hello duniya\"}")
                } else {
                    webSocket.send("{\"message_type\":\"partial_transcript\",\"text\":\"hello\"}")
                    webSocket.send("{\"message_type\":\"partial_transcript\",\"text\":\"hello duni\"}")
                }
            }
        }))
        server.start()

        val recognizer = ElevenLabsRealtimeRecognizer(
            auth = ElevenLabsRealtimeAuth.ApiKey("eleven-test-key"),
            languageCode = "hi",
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )

        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        recognizer.acceptWaveForm(shortArrayOf(3, 4), 2)

        assertTrue(waitUntil { recognizer.getPartialResult() == "hello duni" })
        // MySpeechService interrupts its capture thread to request finish. The realtime bridge
        // must still await the provider, then restore the thread's interruption status.
        Thread.currentThread().interrupt()
        try {
            assertEquals("hello duniya", recognizer.getFinalResult())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("eleven-test-key", request.getHeader("xi-api-key"))
        assertEquals("scribe_v2_realtime", request.requestUrl?.queryParameter("model_id"))
        assertEquals("manual", request.requestUrl?.queryParameter("commit_strategy"))
        assertEquals(listOf("en"), request.requestUrl?.queryParameterValues("secondary_languages"))

        assertEquals(2, receivedAudioMessages.size)
        val first = Json.parseToJsonElement(receivedAudioMessages[0]).jsonObject
        val last = Json.parseToJsonElement(receivedAudioMessages[1]).jsonObject
        assertFalse(first["commit"]?.jsonPrimitive?.booleanOrNull == true)
        assertTrue(last["commit"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(16_000, first["sample_rate"]?.jsonPrimitive?.content?.toInt())
        assertEquals(16_000, last["sample_rate"]?.jsonPrimitive?.content?.toInt())
        assertTrue(last["audio_base_64"]?.jsonPrimitive?.content?.isNotEmpty() == true)
        assertEquals(
            listOf<Byte>(3, 0, 4, 0),
            Base64.getDecoder().decode(last["audio_base_64"]!!.jsonPrimitive.content).toList(),
        )
    }

    @Test
    fun elevenLabsConsumesAPreparedSingleUseTokenForEverySocket() {
        fun responseFor(finalText: String) = MockResponse().withWebSocketUpgrade(
            object : ClosingWebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"message_type\":\"session_started\",\"session_id\":\"test\"}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = Json.parseToJsonElement(text).jsonObject
                    if (json["commit"]?.jsonPrimitive?.booleanOrNull == true) {
                        webSocket.send(
                            "{\"message_type\":\"committed_transcript\",\"text\":\"$finalText\"}"
                        )
                    }
                }
            },
        )
        server.enqueue(responseFor("first"))
        server.enqueue(responseFor("second"))
        server.start()
        val recognizer = ElevenLabsRealtimeRecognizer(
            auth = ElevenLabsRealtimeAuth.SingleUseToken(
                listOf("single-use-1", "single-use-2"),
            ),
            languageCode = null,
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )

        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        assertEquals("first", recognizer.getFinalResult())
        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(3, 4), 2)
        assertEquals("second", recognizer.getFinalResult())

        val firstRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        val secondRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("single-use-1", firstRequest.requestUrl?.queryParameter("token"))
        assertEquals("single-use-2", secondRequest.requestUrl?.queryParameter("token"))
        assertEquals(null, firstRequest.getHeader("xi-api-key"))
        assertEquals(null, secondRequest.getHeader("xi-api-key"))
    }

    @Test
    fun missingPreparedSingleUseTokenFailsImmediatelyInsteadOfBlockingReset() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"message_type\":\"session_started\",\"session_id\":\"test\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = Json.parseToJsonElement(text).jsonObject
                if (json["commit"]?.jsonPrimitive?.booleanOrNull == true) {
                    webSocket.send("{\"message_type\":\"committed_transcript\",\"text\":\"first\"}")
                }
            }
        }))
        server.start()

        val recognizer = ElevenLabsRealtimeRecognizer(
            auth = ElevenLabsRealtimeAuth.SingleUseToken("only-token"),
            languageCode = null,
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 1_000,
        )
        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)
        assertEquals("first", recognizer.getFinalResult())

        val startedAt = System.nanoTime()
        recognizer.reset()
        assertThrows(RealtimeRecognitionException::class.java) {
            recognizer.acceptWaveForm(shortArrayOf(3, 4), 2)
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(elapsedMillis < 250)
        assertEquals(RealtimeConnectionState.FAILED, recognizer.connectionState)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun elevenLabsRecognizesCommitThrottlingAsAProviderError() {
        val protocol = ElevenLabsRealtimeProtocol(
            auth = ElevenLabsRealtimeAuth.ApiKey("test-key"),
            languageCode = null,
            secondaryLanguages = listOf("hi", "en"),
            keyterms = emptyList(),
            endpoint = "wss://example.invalid/realtime",
        )

        assertEquals(
            RealtimeServerEvent.Error("Commit requests are too frequent"),
            protocol.parseServerMessage(
                "{\"message_type\":\"commit_throttled\",\"error\":\"Commit requests are too frequent\"}"
            ),
        )
    }

    @Test
    fun finalizationHasABoundedDeadline() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"message_type\":\"session_started\",\"session_id\":\"test\"}")
            }
        }))
        server.start()

        val recognizer = ElevenLabsRealtimeRecognizer(
            auth = ElevenLabsRealtimeAuth.ApiKey("eleven-test-key"),
            languageCode = null,
            client = client,
            endpoint = server.url("/realtime").toString(),
            finalResultTimeoutMillis = 80,
        )
        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, 2), 2)

        val startedAt = System.nanoTime()
        val error = assertThrows(RealtimeRecognitionException::class.java) {
            recognizer.getFinalResult()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(error.message.orEmpty().contains("ElevenLabs"))
        assertTrue(elapsedMillis >= 60)
        assertTrue(elapsedMillis < 1_000)
        assertEquals(RealtimeConnectionState.FAILED, recognizer.connectionState)
    }

    @Suppress("DEPRECATION")
    @Test
    fun preOpenAudioQueueFailsBeforeItCanGrowWithoutBound() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()

        val recognizer = SarvamRealtimeRecognizer(
            apiKey = "sarvam-test-key",
            languageCode = "hi",
            client = client,
            endpoint = server.url("/realtime").toString(),
            maxPreOpenAudioMillis = 1,
        )
        recognizer.reset()

        val error = assertThrows(RealtimeRecognitionException::class.java) {
            // 20 samples = 40 bytes; the configured 1 ms queue limit is 32 bytes at PCM16/16kHz.
            recognizer.acceptWaveForm(ShortArray(20) { it.toShort() }, 20)
        }

        assertTrue(error.message.orEmpty().contains("queued microphone audio"))
        assertEquals(RealtimeConnectionState.FAILED, recognizer.connectionState)
    }

    private fun waitUntil(timeoutMillis: Long = 1_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private open class ClosingWebSocketListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }
}
