package me.rerere.workspace.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayDatagramSessionTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val hostId = "abcdefghijklmnopqrstuvwxyz"
    private val signer = LocalTestSigner()

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun protocolSerializationParsesCapabilitiesAndUdpEndpoint() {
        val raw = """
            {
              "sid": "0123456789abcdef0123456789abcdef",
              "ch": "media-dgram",
              "pace": {"kbps": 4000, "burstKb": 4096},
              "capabilities": {"dgram": "udp", "udpPort": 8444, "udpHost": "udp.example.com", "stream": true},
              "udp": {"port": 8444, "host": "udp.example.com", "token": "AAAAAAAAAAAAAAAAAAAAAA"}
            }
        """.trimIndent()
        val ready = relayJson.decodeFromString<RelayReady>(raw)
        assertEquals("media-dgram", ready.ch)
        assertEquals(RelayChannel.MEDIA_DGRAM.wire, ready.ch)
        assertEquals("udp", ready.capabilities?.dgram)
        assertEquals(8444, ready.capabilities?.udpPort)
        assertEquals("udp.example.com", ready.capabilities?.udpHost)
        assertEquals(8444, ready.udp?.port)
        assertEquals("udp.example.com", ready.udp?.host)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", ready.udp?.token)
    }

    @Test fun probeForwardingKeepaliveAndCloseUnblocksReceive() {
        val token = ByteArray(16) { (it + 1).toByte() }
        val connector = DgramControlConnector(hostId, signer.publicKeySpki, token)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val control = RelaySocket.open(client, server.url("/").toString(), "code", hostId, RelayChannel.MEDIA_DGRAM, signer)

        val fakeRelaySock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val peerAddr = AtomicReference<SocketAddress?>()
        val emptyKeepalives = AtomicInteger()
        val relayThread = Thread({
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (!fakeRelaySock.isClosed) {
                pkt.length = buf.size
                try {
                    fakeRelaySock.receive(pkt)
                } catch (_: Exception) {
                    break
                }
                peerAddr.set(pkt.socketAddress)
                if (pkt.length < 17) continue
                val flow = buf[16]
                if (flow == RelayDatagramSession.FLOW_PROBE) {
                    if (pkt.length == 17) {
                        emptyKeepalives.incrementAndGet()
                    } else {
                        val reply = buf.copyOf(pkt.length)
                        reply[16] = RelayDatagramSession.FLOW_ACK
                        fakeRelaySock.send(DatagramPacket(reply, reply.size, pkt.socketAddress))
                    }
                    continue
                }
                // Echo media/control/audio payloads prefixed with "relay:".
                val body = "relay:".toByteArray() + buf.copyOfRange(17, pkt.length)
                val out = ByteArray(17 + body.size)
                System.arraycopy(token, 0, out, 0, 16)
                out[16] = flow
                System.arraycopy(body, 0, out, 17, body.size)
                fakeRelaySock.send(DatagramPacket(out, out.size, pkt.socketAddress))
            }
        }, "TestUdpRelay").apply { isDaemon = true; start() }

        val session = RelayDatagramSession(
            controlSocket = control,
            udpHost = "127.0.0.1",
            udpPort = fakeRelaySock.localPort,
            clientToken = token,
            udpSocket = DatagramSocket(),
            keepaliveIntervalMillis = 50L,
        )
        try {
            assertTrue(session.probe(1_500L))

            val rx = ByteArray(2048)
            val flowOut = ByteArray(1)
            for (flow in listOf(
                RelayDatagramSession.FLOW_VIDEO,
                RelayDatagramSession.FLOW_CONTROL,
                RelayDatagramSession.FLOW_AUDIO,
            )) {
                val msg = "hello-$flow".toByteArray()
                assertTrue(session.send(flow, msg))
                val n = session.receive(rx, flowOut)
                assertEquals(flow, flowOut[0])
                assertEquals("relay:hello-$flow", String(rx, 0, n))
            }

            // Inject bad-token packet + unsolicited 0xFE ack and confirm receive() skips them cleanly.
            val dst = peerAddr.get()!!
            val badToken = ByteArray(25) { 0x55 }.also { it[16] = RelayDatagramSession.FLOW_VIDEO }
            fakeRelaySock.send(DatagramPacket(badToken, badToken.size, dst))
            val ignoredAck = token + byteArrayOf(RelayDatagramSession.FLOW_ACK, 1, 2, 3)
            fakeRelaySock.send(DatagramPacket(ignoredAck, ignoredAck.size, dst))
            val valid = token + byteArrayOf(RelayDatagramSession.FLOW_CONTROL) + "after-skip".toByteArray()
            fakeRelaySock.send(DatagramPacket(valid, valid.size, dst))

            val n = session.receive(rx, flowOut)
            assertEquals(RelayDatagramSession.FLOW_CONTROL, flowOut[0])
            assertEquals("after-skip", String(rx, 0, n))

            // Keepalives should fire quickly at 50ms interval.
            val deadline = System.currentTimeMillis() + 1_000L
            while (emptyKeepalives.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("expected empty 0xFF keepalives, got ${emptyKeepalives.get()}", emptyKeepalives.get() >= 2)

            // Blocking receive must unblock and return -1 when the control socket closes.
            val readResult = AtomicInteger(Int.MIN_VALUE)
            val latch = CountDownLatch(1)
            Thread({
                readResult.set(session.receive(rx, flowOut))
                latch.countDown()
            }, "TestUdpReader").start()
            Thread.sleep(30)
            control.close()
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(-1, readResult.get())
            assertTrue(session.isClosed)
        } finally {
            session.close()
            fakeRelaySock.close()
            relayThread.join(1_000)
        }
    }

    @Test fun probeTimeoutReturnsFalseWhenSilent() {
        val connector = DgramControlConnector(hostId, signer.publicKeySpki, ByteArray(16))
        server.enqueue(MockResponse.Builder().webSocketUpgrade(connector).build())
        val control = RelaySocket.open(client, server.url("/").toString(), "code", hostId, RelayChannel.MEDIA_DGRAM, signer)
        val silentSock = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val session = RelayDatagramSession(
            controlSocket = control,
            udpHost = "127.0.0.1",
            udpPort = silentSock.localPort,
            clientToken = ByteArray(16) { 0x07 },
            udpSocket = DatagramSocket(),
        )
        try {
            assertFalse(session.probe(300L))
        } finally {
            session.close()
            silentSock.close()
        }
    }

    private class LocalTestSigner : RelayDeviceSigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKeySpki: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()
    }

    private class DgramControlConnector(
        private val hostId: String,
        private val allowed: ByteArray,
        private val token: ByteArray,
    ) : WebSocketListener() {
        private val sid = "0123456789abcdef0123456789abcdef"
        private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            val tok = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
            webSocket.send(
                """{"t":"ready","sid":"$sid","ch":"media-dgram","pace":{"kbps":4000,"burstKb":4096},"udp":{"port":8444,"token":"$tok"}}""",
            )
            webSocket.send("""{"t":"challenge","v":1,"nonce":"$nonce"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "ping") {
                webSocket.send("pong")
                return
            }
            val msg = Json.parseToJsonElement(text).jsonObject
            if (msg["t"]?.jsonPrimitive?.content != "auth") return
            val device = Base64.getUrlDecoder().decode(msg["device"]!!.jsonPrimitive.content)
            val sig = Base64.getUrlDecoder().decode(msg["sig"]!!.jsonPrimitive.content)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(device))
            val payload = "miffan-relay/v1 session\n$hostId\n$sid\nmedia-dgram\n$nonce".toByteArray()
            val good = device.contentEquals(allowed) &&
                Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload) }.verify(sig)
            if (good) {
                webSocket.send("""{"t":"ok"}""")
            } else {
                webSocket.close(4403, "denied")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }
}
