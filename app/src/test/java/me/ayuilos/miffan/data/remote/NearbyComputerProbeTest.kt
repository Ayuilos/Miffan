package me.ayuilos.miffan.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class NearbyComputerProbeTest {
    @Test
    fun sshBannerIsReadWithoutSendingAnything() = runBlocking {
        val banner = "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13"
        withServer { server ->
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.getOutputStream().write("$banner\r\n".toByteArray())
                    socket.soTimeout = 2_000
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertEquals(banner, probeNearbySsh("127.0.0.1", server.localPort))
            peer.await()
        }
    }

    @Test
    fun httpAndOverlongBannerAreRejected() = runBlocking {
        for (text in listOf("HTTP/1.1 200 OK\r\n", "SSH-" + "x".repeat(256) + "\r\n")) {
            withServer { server ->
                val peer = launch(Dispatchers.IO) {
                    server.accept().use { it.getOutputStream().write(text.toByteArray()) }
                }
                assertNull(probeNearbySsh("127.0.0.1", server.localPort))
                peer.join()
            }
        }
    }

    @Test
    fun silentPeerTimesOutAndSocketIsClosed() = runBlocking {
        withServer { server ->
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertNull(probeNearbySsh("127.0.0.1", server.localPort, bannerTimeoutMillis = 100))
            peer.await()
        }
    }

    @Test
    fun cancellationClosesSocketWithoutWaitingForReadTimeout() = runBlocking {
        withServer { server ->
            val accepted = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    accepted.complete(Unit)
                    socket.soTimeout = 2_000
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            val probe = launch { probeNearbySsh("127.0.0.1", server.localPort, bannerTimeoutMillis = 10_000) }
            withTimeout(2_000) { accepted.await() }
            withTimeout(1_000) { probe.cancelAndJoin() }
            peer.await()
        }
    }

    private suspend fun withServer(block: suspend kotlinx.coroutines.CoroutineScope.(ServerSocket) -> Unit) = coroutineScope {
        withContext(Dispatchers.IO) { ServerSocket(0, 1, InetAddress.getLoopbackAddress()) }.use { block(it) }
    }
}
