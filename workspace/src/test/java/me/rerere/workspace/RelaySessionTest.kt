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
