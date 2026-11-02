package me.rerere.workspace.relay

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelaySocketTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val hostId = "abcdefghijklmnopqrstuvwxyz"
    private val signer = TestSigner()

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun handshakeThenEcho() {
        val connector = FakeConnector(hostId, allowed = signer.publicKeySpki)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val socket = RelaySocket.open(client, server.url("/").toString(), "code", hostId, RelayChannel.CONTROL, signer)
        assertEquals("control", socket.ready.ch)
        assertEquals(1000, socket.ready.pace.kbps)
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        socket.getOutputStream().write(payload)
        val back = ByteArray(payload.size)
        var read = 0
        while (read < back.size) {
            val n = socket.getInputStream().read(back, read, back.size - read)
            assertTrue(n > 0)
            read += n
        }
        assertArrayEquals(payload, back)
        assertTrue(connector.verified)
        val request = server.takeRequest()
        assertEquals("/v1/connect/$hostId?ch=control", request.target)
        assertEquals("Bearer code", request.headers["Authorization"])
        socket.close()
    }

    @Test fun deniedDevice() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeConnector(hostId, allowed = ByteArray(0))).build())
        val error = runCatching {
            RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer)
        }.exceptionOrNull()
        assertTrue(error is RelayException)
        assertEquals("denied", (error as RelayException).reason)
    }

    @Test fun relayRefusalKeepsReason() {
        server.enqueue(MockResponse.Builder().code(404).body("""{"reason":"host_offline"}""").build())
        val error = runCatching {
            RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer)
        }.exceptionOrNull() as RelayException
        assertEquals("host_offline", error.reason)
        assertEquals(404, error.httpStatus)
    }

    @Test fun platformErrorIsNotOffline() {
        server.enqueue(MockResponse.Builder().code(429).body("<html>error code: 1027</html>").build())
        val error = runCatching {
            RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer)
        }.exceptionOrNull() as RelayException
        assertEquals(RelayException.PLATFORM, error.reason)
    }

    @Test fun deviceIdMatchesProtocolDefinition() {
        // First 26 characters of lowercase RFC 4648 base32 of SHA-256("abc").
        assertEquals("xj4bnp4pahh6uqkbidpf3lrceo", RelayIds.deviceId("abc".toByteArray()))
        assertEquals("my", RelayIds.base32Lower(byteArrayOf(0x66)))
        assertEquals("mzxw6", RelayIds.base32Lower("foo".toByteArray()))
    }

    @Test fun queueStaysNearTwoSecondsOfPace() {
        assertEquals(400_000L, RelaySocket.maxQueuedBytes(RelayPace(kbps = 1600, burstKb = 2048)))
        assertEquals(64L * 1024, RelaySocket.maxQueuedBytes(RelayPace(kbps = 100, burstKb = 64)))
        assertEquals(1L shl 20, RelaySocket.maxQueuedBytes(RelayPace()))
    }

    /**
     * Uploading faster than the link carries must not delay the relay's `pong` past the grace
     * period: a 2 MiB queue at this pace and link held a `ping` back for 21 s or more; now it is
     * about 5 s (more on a loaded machine).
     */
    @Test fun pingIsAnsweredWhileTheUploadOutrunsTheLink() {
        val connector = FakeConnector(hostId, allowed = signer.publicKeySpki, pace = """{"kbps":1600,"burstKb":2048}""",
            echo = false)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        ThrottlingProxy(server.port, upstreamBytesPerSecond = 100_000).use { proxy ->
            val socket = RelaySocket.open(smallBufferClient(), "http://127.0.0.1:${proxy.port}/", null, hostId,
                RelayChannel.CONTROL, signer)
            val upload = Thread {
                runCatching { repeat(64) { socket.getOutputStream().write(ByteArray(64 * 1024)) } }
            }.apply { isDaemon = true; start() }
            Thread.sleep(1_500) // the 2 MiB burst is queued by now
            val millis = socket.ping(timeoutMillis = 20_000)
            socket.close()
            upload.join(5_000)
            assertTrue("pong took $millis ms", millis != null && millis < 15_000)
        }
    }

    @Test fun anyFrameFromTheRelayCountsAsAlive() {
        val connector = FakeConnector(hostId, allowed = signer.publicKeySpki, answerPings = false, chatterMillis = 50)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val socket = RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer,
            timings = RelayTimings(pingMillis = 200, graceMillis = 100))
        Thread.sleep(1_500)
        assertTrue("closed although the relay kept sending", !socket.isClosed)
        socket.close()
    }

    @Test fun silenceAfterAPingCloses() {
        val connector = FakeConnector(hostId, allowed = signer.publicKeySpki, answerPings = false)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val socket = RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer,
            timings = RelayTimings(pingMillis = 200, graceMillis = 100))
        Thread.sleep(1_000)
        assertTrue(socket.isClosed)
        assertEquals(-1, socket.getInputStream().read())
    }

    /** Small socket buffers, so the kernel does not hide a slow link from the test. */
    private fun smallBufferClient(): OkHttpClient = client.newBuilder().socketFactory(object : javax.net.SocketFactory() {
        private fun Socket.small() = apply { sendBufferSize = 16 * 1024; receiveBufferSize = 16 * 1024 }
        override fun createSocket(): Socket = Socket().small()
        override fun createSocket(host: String, port: Int): Socket = Socket(host, port).small()
        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            Socket(host, port, localHost, localPort).small()
        override fun createSocket(host: InetAddress, port: Int): Socket = Socket(host, port).small()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            Socket(address, port, localAddress, localPort).small()
    }).build()

    @Test fun checkAliveClosesASilentSession() {
        val connector = FakeConnector(hostId, allowed = signer.publicKeySpki, answerPings = false)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val socket = RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer)
        val started = System.nanoTime()
        assertTrue(!socket.checkAlive(timeoutMillis = 500))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertTrue(socket.isClosed)
        assertEquals(RelayException.UNREACHABLE, socket.closedByRelay?.reason)
    }

    @Test fun checkAliveKeepsAnAnsweringSession() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeConnector(hostId, allowed = signer.publicKeySpki)).build())
        val socket = RelaySocket.open(client, server.url("/").toString(), null, hostId, RelayChannel.CONTROL, signer)
        assertTrue(socket.checkAlive(timeoutMillis = 2_000))
        assertTrue(!socket.isClosed)
        socket.close()
    }

    @Test fun clientChecksEveryOpenSessionAfterANetworkChange() {
        server.enqueue(MockResponse.Builder().body("""{"v":1,"edges":[]}""").build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeConnector(hostId, allowed = signer.publicKeySpki)).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(
            FakeConnector(hostId, allowed = signer.publicKeySpki, answerPings = false)).build())
        val relay = RelayClient(client)
        val endpoint = RelayEndpoint(server.url("/").toString().trimEnd('/'))
        val answering = relay.open(endpoint, hostId, RelayChannel.CONTROL, signer, "wifi:1")
        val silent = relay.open(endpoint, hostId, RelayChannel.CONTROL, signer, "wifi:1")
        assertEquals(1, relay.checkLiveSessions(timeoutMillis = 500).get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(silent.isClosed)
        assertTrue(!answering.isClosed)
        answering.close()
        // A closed session is no longer checked.
        assertEquals(0, relay.checkLiveSessions(timeoutMillis = 500).get(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun tokenBucketPaces() {
        val bucket = TokenBucket(kbps = 800, burstKb = 64) // 100 KB/s, 64 KiB burst
        val start = System.nanoTime()
        repeat(4) { bucket.take(64 * 1024) }
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("took $seconds s", seconds in 1.5..3.5) // 192 KiB beyond the burst at 100 KB/s ≈ 2 s
    }
}

/** A TCP proxy to 127.0.0.1:[target] whose client-to-server direction carries [upstreamBytesPerSecond]. */
private class ThrottlingProxy(private val target: Int, private val upstreamBytesPerSecond: Int) : Closeable {
    private val listener = ServerSocket().apply { receiveBufferSize = 16 * 1024; bind(InetSocketAddress("127.0.0.1", 0)) }
    private val open = mutableListOf<Socket>()
    val port: Int get() = listener.localPort

    init {
        Thread {
            runCatching {
                while (true) {
                    val client = listener.accept()
                    val upstream = Socket("127.0.0.1", target)
                    synchronized(open) { open += client; open += upstream }
                    pump(client, upstream, upstreamBytesPerSecond)
                    pump(upstream, client, 0)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun pump(from: Socket, to: Socket, bytesPerSecond: Int) = Thread {
        runCatching {
            val buffer = ByteArray(4 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                if (bytesPerSecond > 0) Thread.sleep(n * 1000L / bytesPerSecond)
            }
        }
        runCatching { to.shutdownOutput() }
    }.apply { isDaemon = true; start() }

    override fun close() {
        runCatching { listener.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } } }
    }
}

private class TestSigner : RelayDeviceSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKeySpki: ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()
}

/** Plays both the relay (`ready`) and the connector (challenge, verify, echo) on one WebSocket. */
private class FakeConnector(
    private val hostId: String,
    private val allowed: ByteArray,
    private val pace: String = """{"kbps":1000,"burstKb":2048}""",
    private val echo: Boolean = true,
    private val answerPings: Boolean = true,
    /** After `ok`, sends a small binary frame this often (0 = never). */
    private val chatterMillis: Long = 0,
) : WebSocketListener() {
    private val sid = "0123456789abcdef0123456789abcdef"
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    @Volatile var verified = false
    private var open = false

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        webSocket.send("""{"t":"ready","sid":"$sid","ch":"control","pace":$pace,"usage":{"dayMb":1.5}}""")
        webSocket.send("""{"t":"challenge","v":1,"nonce":"$nonce"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (text == "ping") { if (answerPings) webSocket.send("pong"); return }
        val msg = Json.parseToJsonElement(text).jsonObject
        if (msg["t"]?.jsonPrimitive?.content != "auth") return
        val device = Base64.getUrlDecoder().decode(msg["device"]!!.jsonPrimitive.content)
        val sig = Base64.getUrlDecoder().decode(msg["sig"]!!.jsonPrimitive.content)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(device))
        val payload = "miffan-relay/v1 session\n$hostId\n$sid\ncontrol\n$nonce".toByteArray()
        val good = device.contentEquals(allowed) &&
            Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload) }.verify(sig)
        if (good) {
            verified = true
            open = true
            webSocket.send("""{"t":"ok"}""")
            if (chatterMillis > 0) Thread {
                while (webSocket.send(ByteString.of(1, 2, 3))) Thread.sleep(chatterMillis)
            }.apply { isDaemon = true }.start()
        } else {
            webSocket.send("""{"t":"denied","reason":"unknown_device"}""")
            webSocket.close(4403, "denied")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (open && echo) webSocket.send(bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }
}
