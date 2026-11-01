package me.rerere.workspace.relay

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

    @Test fun tokenBucketPaces() {
        val bucket = TokenBucket(kbps = 800, burstKb = 64) // 100 KB/s, 64 KiB burst
        val start = System.nanoTime()
        repeat(4) { bucket.take(64 * 1024) }
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue("took $seconds s", seconds in 1.5..3.5) // 192 KiB beyond the burst at 100 KB/s ≈ 2 s
    }
}

private class TestSigner : RelayDeviceSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKeySpki: ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()
}

/** Plays both the relay (`ready`) and the connector (challenge, verify, echo) on one WebSocket. */
private class FakeConnector(private val hostId: String, private val allowed: ByteArray) : WebSocketListener() {
    private val sid = "0123456789abcdef0123456789abcdef"
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    @Volatile var verified = false
    private var open = false

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        webSocket.send("""{"t":"ready","sid":"$sid","ch":"control","pace":{"kbps":1000,"burstKb":2048},"usage":{"dayMb":1.5}}""")
        webSocket.send("""{"t":"challenge","v":1,"nonce":"$nonce"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (text == "ping") { webSocket.send("pong"); return }
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
        } else {
            webSocket.send("""{"t":"denied","reason":"unknown_device"}""")
            webSocket.close(4403, "denied")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (open) webSocket.send(bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }
}
