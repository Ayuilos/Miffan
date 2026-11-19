package me.rerere.workspace.relay

import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayStreamTest {
    private val hostId = "abcdefghijklmnopqrstuvwxyz"

    private class LocalSigner : RelayDeviceSigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override val publicKeySpki: ByteArray = pair.public.encoded

        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    @Test
    fun codecAndFramerHandleFramingEdgeCases() {
        val bin = ByteArray(1024) { (it * 7).toByte() }
        val longReason = "中".repeat(50) // 150 UTF-8 bytes
        val closeBytes = RelayStreamCodec.encodeClose(4401, longReason)
        assertTrue(closeBytes.size - 5 <= 125)

        val wire = RelayStreamCodec.PRELUDE +
            RelayStreamCodec.encodeText("hello 世界") +
            RelayStreamCodec.encodeBinary(bin) +
            closeBytes

        val whole = RelayStreamFramer()
        val evWhole = whole.push(wire)
        assertEquals(4, evWhole.size)
        assertEquals(RelayStreamFramer.Event.Prelude, evWhole[0])
        assertEquals(RelayStreamFramer.Event.Text("hello 世界"), evWhole[1])
        assertArrayEquals(bin, (evWhole[2] as RelayStreamFramer.Event.Binary).data.toByteArray())
        assertEquals(4401, (evWhole[3] as RelayStreamFramer.Event.Close).code)
        assertTrue(whole.isHalted)
        assertFalse(whole.hasPartial())

        // Drip one byte at a time.
        val byteFramer = RelayStreamFramer()
        val evByte = ArrayList<RelayStreamFramer.Event>()
        for (i in wire.indices) {
            evByte += byteFramer.push(wire, i, 1)
        }
        assertEquals(evWhole.size, evByte.size)
        assertEquals(RelayStreamFramer.Event.Text("hello 世界"), evByte[1])

        // Oversize binary (> 65536 and unsigned >= 2 GiB) rejects on header without payload allocation.
        val bigHeader = byteArrayOf(0x02, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        assertEquals(listOf(RelayStreamFramer.Event.Oversize), RelayStreamFramer().push(bigHeader))
        val signBitHeader = byteArrayOf(0x02, 0x80.toByte(), 0, 0, 0)
        assertEquals(listOf(RelayStreamFramer.Event.Oversize), RelayStreamFramer().push(signBitHeader))
        val bigTextHeader = byteArrayOf(0x01, 0, 0, 0x10, 0x01)
        assertEquals(listOf(RelayStreamFramer.Event.Oversize), RelayStreamFramer().push(bigTextHeader))

        // Invalid UTF-8 text is rejected.
        val badUtf8 = byteArrayOf(0x01, 0, 0, 0, 2, 0xff.toByte(), 0xfe.toByte())
        assertEquals(listOf(RelayStreamFramer.Event.Invalid("invalid_utf8")), RelayStreamFramer().push(badUtf8))

        // Bad prelude length is rejected.
        val badPrelude = byteArrayOf(0x00, 0, 0, 0, 1)
        assertEquals(listOf(RelayStreamFramer.Event.Invalid("bad_prelude")), RelayStreamFramer().push(badPrelude))
    }

    @Test
    fun openStreamCompletesHandshakeEchoesAndMapsErrors() {
        val signer = LocalSigner()
        val transport = InMemoryStreamTransport(hostId, signer.publicKeySpki)

        val socket = RelaySocket.openStream(
            transport = transport,
            baseUrl = "https://relay.example:8443",
            accessToken = "tok",
            hostId = hostId,
            channel = RelayChannel.CONTROL,
            signer = signer,
        )
        assertEquals("control", socket.ready.ch)
        assertTrue(transport.sawPrelude.get())
        assertNotNull(socket.ping(2_000))

        val payload = ByteArray(150_000) { (it % 239).toByte() }
        socket.getOutputStream().write(payload)
        val back = ByteArray(payload.size)
        var read = 0
        while (read < back.size) {
            val n = socket.getInputStream().read(back, read, back.size - read)
            assertTrue(n > 0)
            read += n
        }
        assertArrayEquals(payload, back)
        socket.close()

        // Non-200 JSON refusal preserves exact protocol reason and HTTP status.
        transport.nextHttpError = 404 to """{"reason":"host_offline","retryAfter":30}"""
        try {
            RelaySocket.openStream(
                transport = transport,
                baseUrl = "https://relay.example:8443",
                accessToken = null,
                hostId = hostId,
                channel = RelayChannel.CONTROL,
                signer = signer,
            )
            fail("Expected RelayException")
        } catch (e: RelayException) {
            assertEquals("host_offline", e.reason)
            assertEquals(404, e.httpStatus)
            assertEquals(30, e.retryAfterSeconds)
        }
    }

    @Test
    fun relayClientDownloadsCronetOnDemandWhenTcpFailsAndAvoidsDuplicateConnectOn4xx() {
        val signer = LocalSigner()
        val ready = AtomicBoolean(false)
        val ensureCalls = AtomicInteger(0)
        val transport = object : InMemoryStreamTransport(hostId, signer.publicKeySpki) {
            override fun isAvailable(): Boolean = ready.get()
            override fun ensureReady(): Boolean {
                ensureCalls.incrementAndGet()
                ready.set(true)
                return true
            }
        }

        // Closed TCP port simulates ISP blocking TCP while H3/UDP stream succeeds after ensureReady().
        val closedServer = MockWebServer().apply { start() }
        val unreachableUrl = "http://127.0.0.1:${closedServer.port}"
        closedServer.close()

        val client = RelayClient(streamTransport = transport)
        val socket = client.open(
            endpoint = RelayEndpoint(unreachableUrl, "tok"),
            hostId = hostId,
            channel = RelayChannel.CONTROL,
            signer = signer,
            networkKey = "wifi:test",
        )
        assertTrue(ensureCalls.get() >= 1)
        assertEquals(1, transport.openAttempts.get())
        assertTrue(socket.isConnected)
        socket.close()

        // 404 host_offline from stream must NOT fall back to a second WebSocket /v1/connect request.
        val wsServer = MockWebServer().apply {
            start()
            enqueue(MockResponse.Builder().code(200).body("""{"v":1,"impl":"bun","capabilities":{"dgram":"none","stream":true}}""").build())
        }
        try {
            val streamErrTransport = object : InMemoryStreamTransport(hostId, signer.publicKeySpki) {
                override fun isAvailable(): Boolean = true
            }
            streamErrTransport.nextHttpError = 404 to """{"reason":"host_offline"}"""
            val wsClient = RelayClient(OkHttpClient(), streamTransport = streamErrTransport)
            val ep = RelayEndpoint(wsServer.url("/").toString().trimEnd('/'))
            assertTrue(wsClient.info(ep).capabilities.stream)
            val err = runCatching {
                wsClient.open(ep, hostId, RelayChannel.CONTROL, signer, "wifi:test")
            }.exceptionOrNull() as RelayException
            assertEquals("host_offline", err.reason)
            assertTrue("expected no /v1/connect fallback, got ${wsServer.requestCount}", wsServer.requestCount <= 1)
            if (wsServer.requestCount == 1) {
                assertEquals("/v1/info", wsServer.takeRequest().target)
            }
        } finally {
            wsServer.close()
        }

        // Stream transport network error falls back cleanly to WebSocket.
        val fallbackServer = MockWebServer().apply {
            start()
            enqueue(MockResponse.Builder().code(200).body("""{"v":1,"impl":"bun","capabilities":{"dgram":"none","stream":true}}""").build())
            enqueue(MockResponse.Builder().webSocketUpgrade(FakeWsEcho(hostId)).build())
        }
        val fallbackOkClient = OkHttpClient()
        try {
            val failingStream = object : InMemoryStreamTransport(hostId, signer.publicKeySpki) {
                override fun isAvailable(): Boolean = true
                override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): RelayHttpResponse {
                    throw IOException("udp blocked")
                }
                override fun openStream(
                    url: String,
                    headers: Map<String, String>,
                    connectTimeoutMillis: Int,
                    listener: RelayRawStreamListener,
                ): RelayRawStream {
                    openAttempts.incrementAndGet()
                    val dummy = object : RelayRawStream {
                        override fun send(bytes: ByteArray) = false
                        override fun queuedBytes() = 0L
                        override fun close(code: Int, reason: String) = Unit
                    }
                    listener.onFailure(dummy, IOException("udp blocked"))
                    return dummy
                }
            }
            val fbClient = RelayClient(fallbackOkClient, streamTransport = failingStream)
            val sock = fbClient.open(
                RelayEndpoint(fallbackServer.url("/").toString().trimEnd('/')),
                hostId,
                RelayChannel.CONTROL,
                signer,
                "wifi:test",
            )
            assertEquals(1, failingStream.openAttempts.get())
            assertTrue(sock.isConnected)
            sock.close()
        } finally {
            fallbackOkClient.dispatcher.executorService.shutdown()
            fallbackOkClient.connectionPool.evictAll()
            fallbackServer.close()
        }
    }

    private open class InMemoryStreamTransport(
        private val hostId: String,
        private val allowedSpki: ByteArray,
    ) : RelayStreamTransport {
        val sawPrelude = AtomicBoolean(false)
        val openAttempts = AtomicInteger(0)
        @Volatile var nextHttpError: Pair<Int, String>? = null

        override fun isAvailable(): Boolean = true

        override fun get(url: String, headers: Map<String, String>, timeoutMillis: Int): RelayHttpResponse {
            return RelayHttpResponse(200, """{"v":1,"impl":"bun","access":"open","capabilities":{"dgram":"none","stream":true},"edges":[]}""")
        }

        override fun openStream(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMillis: Int,
            listener: RelayRawStreamListener,
        ): RelayRawStream {
            openAttempts.incrementAndGet()
            val framer = RelayStreamFramer()
            val closed = AtomicBoolean(false)
            val errPair = nextHttpError
            nextHttpError = null
            val stream = object : RelayRawStream {
                override fun send(bytes: ByteArray): Boolean {
                    if (closed.get()) return false
                    for (ev in framer.push(bytes)) {
                        when (ev) {
                            RelayStreamFramer.Event.Prelude -> sawPrelude.set(true)
                            is RelayStreamFramer.Event.Text -> {
                                if (ev.data == "ping") {
                                    val pong = RelayStreamCodec.encodeText("pong")
                                    listener.onBytes(this, pong, 0, pong.size)
                                } else {
                                    val json = Json.parseToJsonElement(ev.data).jsonObject
                                    if (json["t"]?.jsonPrimitive?.content == "auth") {
                                        val ok = RelayStreamCodec.encodeText("""{"t":"ok"}""")
                                        listener.onBytes(this, ok, 0, ok.size)
                                    }
                                }
                            }
                            is RelayStreamFramer.Event.Binary -> {
                                val echo = RelayStreamCodec.encodeBinary(ev.data.toByteArray())
                                listener.onBytes(this, echo, 0, echo.size)
                            }
                            is RelayStreamFramer.Event.Close -> close(ev.code, ev.reason)
                            else -> close(1002, "bad")
                        }
                    }
                    return true
                }

                override fun queuedBytes(): Long = 0L

                override fun close(code: Int, reason: String) {
                    if (closed.compareAndSet(false, true)) listener.onClosed(this)
                }
            }
            if (errPair != null) {
                listener.onHttpError(stream, errPair.first, errPair.second)
                return stream
            }
            listener.onOpen(stream)
            val ready = RelayStreamCodec.encodeText(
                """{"t":"ready","sid":"0123456789abcdef0123456789abcdef","ch":"control","pace":{"kbps":0,"burstKb":0}}""",
            )
            val challenge = RelayStreamCodec.encodeText("""{"t":"challenge","v":1,"nonce":"nonce123"}""")
            val boot = RelayStreamCodec.PRELUDE + ready + challenge
            listener.onBytes(stream, boot, 0, boot.size)
            val _unused = hostId.length + allowedSpki.size
            return stream
        }
    }

    private class FakeWsEcho(private val hostId: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"t":"ready","sid":"0123456789abcdef0123456789abcdef","ch":"control"}""")
            webSocket.send("""{"t":"challenge","v":1,"nonce":"nonce1"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                webSocket.send("pong")
                return
            }
            val msg = Json.parseToJsonElement(text).jsonObject
            if (msg["t"]?.jsonPrimitive?.content == "auth") {
                webSocket.send("""{"t":"ok"}""")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.send(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }
}
