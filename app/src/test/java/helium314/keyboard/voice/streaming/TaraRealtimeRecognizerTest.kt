package helium314.keyboard.voice.streaming

import com.elishaazaria.sayboard.data.SpeakKeysLocale
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaraRealtimeRecognizerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        runCatching { server.shutdown() }
    }

    @Test
    fun streamsRawPcmBeforeReleaseAndReturnsOneFinalTranscript() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val audio = Collections.synchronizedList(mutableListOf<ByteArray>())
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "{\"type\":\"session.ready\",\"protocol_version\":1," +
                        "\"sample_rate\":16000}",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
                events += type.orEmpty()
                if (type == "input.commit") {
                    webSocket.send(
                        "{\"type\":\"transcript.final\"," +
                            "\"text\":\"आज meeting अच्छी थी\"}",
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events += "audio"
                audio += bytes.toByteArray()
            }
        }))
        server.start()

        val recognizer = TaraRealtimeRecognizer(
            bearerToken = "firebase-id-token",
            languageCode = "hi",
            client = client,
            endpoint = server.url("/v1/realtime").toString(),
            allowInsecureEndpoint = true,
            finalResultTimeoutMillis = 1_000,
        )
        recognizer.reset()
        recognizer.acceptWaveForm(shortArrayOf(1, -2, 3), 3)

        assertEquals("आज meeting अच्छी थी", recognizer.getFinalResult())
        assertEquals(listOf("session.start", "audio", "input.commit"), events)
        assertEquals(listOf<Byte>(1, 0, -2, -1, 3, 0), audio.single().toList())

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer firebase-id-token", request.getHeader("Authorization"))
        assertEquals("1", request.getHeader(TaraRealtimeProtocol.PROTOCOL_HEADER))
        recognizer.cancelCurrentUtterance()
    }

    @Test
    fun sourceFetchesAuthenticationBeforeOpeningItsPreparedSocket() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "{\"type\":\"session.ready\",\"protocol_version\":1," +
                        "\"sample_rate\":16000}",
                )
            }
        }))
        server.start()
        val tokenCalls = AtomicInteger()
        val source = TaraRealtimeRecognizerSource(
            tokenProvider = { _ ->
                tokenCalls.incrementAndGet()
                "fresh-firebase-token"
            },
            endpoint = server.url("/v1/realtime").toString(),
            displayLocale = SpeakKeysLocale("hi", "IN"),
            client = client,
            handshakeTimeoutMillis = 1_000,
            allowInsecureEndpoint = true,
        )

        runBlocking { source.initialize() }

        assertEquals(RecognizerState.READY, source.stateFlow.value)
        assertFalse(source.closed)
        assertEquals(1, tokenCalls.get())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer fresh-firebase-token", request.getHeader("Authorization"))
        source.close(freeRAM = true)
    }

    @Test
    fun replacementPrewarmFetchesTokenAgain() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "{\"type\":\"session.ready\",\"protocol_version\":1," +
                        "\"sample_rate\":16000}",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"type\":\"input.commit\"")) {
                    webSocket.send("{\"type\":\"transcript.final\",\"text\":\"नमस्ते\"}")
                }
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "{\"type\":\"session.ready\",\"protocol_version\":1," +
                        "\"sample_rate\":16000}",
                )
            }
        }))
        server.start()
        val tokenCalls = AtomicInteger()
        val source = TaraRealtimeRecognizerSource(
            tokenProvider = { _ -> "token-${tokenCalls.incrementAndGet()}" },
            endpoint = server.url("/v1/realtime").toString(),
            client = client,
            handshakeTimeoutMillis = 1_000,
            allowInsecureEndpoint = true,
        )

        runBlocking { source.initialize() }
        source.recognizer.reset()
        source.recognizer.acceptWaveForm(shortArrayOf(7, 8), 2)
        assertEquals("नमस्ते", source.recognizer.getFinalResult())
        source.close(freeRAM = false)

        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val replacement = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        assertEquals("Bearer token-2", replacement.getHeader("Authorization"))
        assertEquals(2, tokenCalls.get())
        source.close(freeRAM = true)
    }

    @Test
    fun authenticationRejectionForcesExactlyOneTokenRefresh() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "{\"type\":\"session.ready\",\"protocol_version\":1," +
                        "\"sample_rate\":16000}",
                )
            }
        }))
        server.start()
        val refreshRequests = Collections.synchronizedList(mutableListOf<Boolean>())
        val source = TaraRealtimeRecognizerSource(
            tokenProvider = { forceRefresh ->
                refreshRequests += forceRefresh
                if (forceRefresh) "fresh-token" else "stale-token"
            },
            endpoint = server.url("/v1/realtime").toString(),
            client = client,
            handshakeTimeoutMillis = 1_000,
            allowInsecureEndpoint = true,
        )

        runBlocking { source.initialize() }

        assertEquals(RecognizerState.READY, source.stateFlow.value)
        assertEquals(listOf(false, true), refreshRequests)
        assertEquals(
            "Bearer stale-token",
            server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"),
        )
        assertEquals(
            "Bearer fresh-token",
            server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"),
        )
        source.close(freeRAM = true)
    }

    @Test
    fun missingAuthenticationFailsWithoutOpeningANetworkConnection() {
        val source = TaraRealtimeRecognizerSource(
            tokenProvider = { _ -> null },
            endpoint = "wss://example.invalid/v1/realtime",
            client = client,
        )

        runBlocking { source.initialize() }

        assertEquals(RecognizerState.ERROR, source.stateFlow.value)
        assertTrue(source.closed)
        assertTrue(source.hasRecoverableAuthFailure)
        assertTrue(source.errorMessage.contains("Sign in"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun protocolIgnoresUnknownEventsAndSurfacesStableServerErrors() {
        val protocol = TaraRealtimeProtocol(
            bearerToken = "token",
            endpoint = "wss://voice.example.com/v1/realtime",
        )

        assertEquals(
            RealtimeServerEvent.Ignored,
            protocol.parseServerMessage("{\"type\":\"metrics\",\"queue_ms\":3}"),
        )
        assertEquals(
            RealtimeServerEvent.Error("Natural Hinglish is busy; try again"),
            protocol.parseServerMessage(
                "{\"type\":\"error\",\"code\":\"busy\"," +
                    "\"message\":\"Natural Hinglish is busy; try again\"}",
            ),
        )
        assertEquals(
            RealtimeServerEvent.Error(
                "Natural Hinglish returned an incompatible voice session",
            ),
            protocol.parseServerMessage(
                "{\"type\":\"session.ready\",\"protocol_version\":2," +
                    "\"sample_rate\":16000}",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun releaseProtocolRejectsPlaintextWebSocketEndpoint() {
        TaraRealtimeProtocol(
            bearerToken = "sensitive-firebase-token",
            endpoint = "ws://voice.example.com/v1/realtime",
            allowInsecureEndpoint = false,
        )
    }
}
