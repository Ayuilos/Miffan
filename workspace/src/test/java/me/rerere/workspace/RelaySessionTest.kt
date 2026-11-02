package me.rerere.workspace

import me.rerere.workspace.relay.RelayChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSH through a relay: idle keepalives and close reasons (relay/PROTOCOL.md §5.3, §5.4, §9). */
class RelaySessionTest {
    private val sshd = TestSshd()

    @After fun stop() = sshd.close()

    @Test fun keepsOnlyABusySessionAlive() {
        // idleTimeoutS 3: a keepalive would go out after 1.5 s of quiet, checked every second.
        FakeRelayBridge(sshd.server.port, idleTimeoutS = 3).use { bridge ->
            val (session, socket) = openThroughBridge(sshd, bridge)
            session.use {
                Thread.sleep(3_500)
                assertTrue("an idle session sent SSH bytes", socket.binaryQuietMillis() >= 3_000)

                val process = session.openProcess("cat")
                var loudest = 0L
                repeat(25) { Thread.sleep(200); loudest = maxOf(loudest, socket.binaryQuietMillis()) }
                assertTrue("busy session stayed quiet for $loudest ms", loudest < 2_900)

                process.close()
                Thread.sleep(3_500)
                assertTrue("closed process still kept the session alive", socket.binaryQuietMillis() >= 3_000)
                // Pings went out, but never any other text after the handshake.
                assertEquals(emptyList<String>(), bridge.textAfterOk.toList())
            }
        }
    }

    @Test fun relayCloseReasonReachesTheSession() {
        FakeRelayBridge(sshd.server.port).use { bridge ->
            val (session, socket) = openThroughBridge(sshd, bridge)
            val heard = java.util.concurrent.LinkedBlockingQueue<String>()
            socket.addCloseListener { heard += it.closeReason ?: "none" }
            assertNull(socket.closedByRelay)
            bridge.relaySide!!.close(4429, "quota_exhausted")
            assertEquals("quota_exhausted", heard.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            val closed = requireNotNull(socket.closedByRelay)
            assertEquals("quota_exhausted", closed.reason)
            assertEquals(RelayChannel.CONTROL, closed.channel)
            val until = System.nanoTime() + 5_000_000_000L
            while (session.isConnected && System.nanoTime() < until) Thread.sleep(50)
            assertTrue(!session.isConnected)
            assertEquals("quota_exhausted", session.relayClose?.reason)
            session.close()
        }
    }

    @Test fun localCloseIsNotARelayClose() {
        FakeRelayBridge(sshd.server.port).use { bridge ->
            val (session, socket) = openThroughBridge(sshd, bridge)
            session.close()
            Thread.sleep(200)
            assertNull(socket.closedByRelay)
        }
    }
}

class RelayRoundTripTest {
    private val sshd = TestSshd()

    @After fun stop() = sshd.close()

    /** Packets the phone sends to open a session (each a round trip's worth on a relay). */
    private fun packetsToOpen(open: (FakeRelayBridge) -> RemoteWorkspaceSession): Int =
        FakeRelayBridge(sshd.server.port).use { bridge ->
            open(bridge).use { session ->
                assertTrue(session.isConnected)
                bridge.binaryFromPhone.get()
            }
        }

    @Test fun keySignInSkipsTheNoneRequestAndTheKeyQuery() {
        val plain = packetsToOpen { openThroughBridge(sshd, it).first }
        val fewer = packetsToOpen { bridge ->
            bridge.expectSession()
            var socket: me.rerere.workspace.relay.RelaySocket? = null
            NativeSshWorkspaceTransport.open(sshd.config(), keepAlive = false, fewerRoundTrips = true,
                socketFactory = bridgeFactory(bridge) { socket = it },
                route = { RemoteRoute.Relay(bridge.endpoint, "abcdefghijklmnopqrstuvwxyz", requireNotNull(socket)) })
        }
        assertTrue("$plain packets before, $fewer after", fewer <= plain - 2)
    }

    @Test fun aSecondSessionToTheSameHostSkipsTheRootCheck() {
        FakeRelayBridge(sshd.server.port).use { bridge ->
            val (control, _) = openThroughBridge(sshd, bridge)
            control.use {
                val before = bridge.binaryFromPhone.get()
                bridge.expectSession()
                var socket: me.rerere.workspace.relay.RelaySocket? = null
                val media = NativeSshWorkspaceTransport.open(sshd.config().copy(remoteRoot = "/"), keepAlive = false,
                    fewerRoundTrips = true, sameHostAs = control, socketFactory = bridgeFactory(bridge) { socket = it },
                    route = { RemoteRoute.Relay(bridge.endpoint, "abcdefghijklmnopqrstuvwxyz", requireNotNull(socket)) })
                media.use {
                    assertEquals(control.platform, media.platform)
                    assertEquals(control.homeDirectory, media.homeDirectory)
                    val mediaPackets = bridge.binaryFromPhone.get() - before
                    val controlPackets = before
                    assertTrue("media $mediaPackets packets, control $controlPackets", mediaPackets <= controlPackets - 6)
                    // Still usable for processes.
                    media.openProcess("cat").close()
                }
            }
        }
    }
}

internal fun bridgeFactory(bridge: FakeRelayBridge, onOpen: (me.rerere.workspace.relay.RelaySocket) -> Unit) =
    object : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): java.net.Socket =
            me.rerere.workspace.relay.RelaySocket.open(okhttp3.OkHttpClient(), bridge.endpoint.baseUrl, null,
                "abcdefghijklmnopqrstuvwxyz", RelayChannel.CONTROL, AnyDevice).also(onOpen)
        override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
        override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
    }

class RelayHeldStreamTest {
    private val sshd = TestSshd()

    @After fun stop() = sshd.close()

    @Test fun aStreamThatNoLongerHoldsTheSessionLetsItRest() {
        FakeRelayBridge(sshd.server.port, idleTimeoutS = 3).use { bridge ->
            val (session, socket) = openThroughBridge(sshd, bridge)
            session.use {
                val process = session.openProcess("cat")
                process.holdsSessionOpen = false
                Thread.sleep(3_500)
                assertTrue("a released stream still kept the session alive", socket.binaryQuietMillis() >= 3_000)
                process.close()
            }
        }
    }
}
