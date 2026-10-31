package me.rerere.workspace

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAddressFallbackTest {
    // JSch exposes the exception types but keeps their constructors package-private.
    private fun <T : JSchException> hostKeyFailure(type: Class<T>, message: String): T =
        type.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(message)

    @Test fun recognizesOnlyNetworkFailuresIncludingJschWrappers() {
        listOf(UnknownHostException("missing"), ConnectException("refused"), NoRouteToHostException(),
            SocketTimeoutException(), SocketException("Network is unreachable"),
            JSchException("timeout: socket is not established"), JSchException("connect", UnknownHostException()),
            JSchException("connection is closed by foreign host"),
            JSchException("Session.connect: java.net.SocketException: Connection reset", SocketException("Connection reset")),
            RemoteConnectException("host", JSchException("java.net.ConnectException: Connection refused"))).forEach {
            assertTrue(it.toString(), isRemoteNetworkFailure(it))
        }
        listOf(JSchException("Auth fail"), JSchException("Auth cancel"), JSchException("channel is closed"),
            JSchException("Session.connect: timeout during authentication", SocketTimeoutException()),
            IllegalArgumentException("configuration"), CancellationException("cancelled"),
            hostKeyFailure(JSchChangedHostKeyException::class.java, "connection timed out"), hostKeyFailure(JSchUnknownHostKeyException::class.java, "connection refused"),
            RemoteConnectException("host", hostKeyFailure(JSchChangedHostKeyException::class.java, "mismatch")),
            RemoteConnectException("host", JSchException("Auth fail"), RemotePasswordSignInFailure.REJECTED, "user")).forEach {
            assertFalse(it.toString(), isRemoteNetworkFailure(it))
        }
    }

    @Test fun anyFailureTriesTheNextAddressExceptRefusedSignInUnconfirmedIdentityAndCancellation() {
        listOf(ConnectException("refused"), JSchException("connection is closed by foreign host"), JSchException("channel is closed"),
            JSchException("Session.connect: timeout during authentication", SocketTimeoutException()),
            JSchException("invalid server's version string"), IllegalArgumentException("bad address"),
            RemoteConnectException("host", hostKeyFailure(JSchChangedHostKeyException::class.java, "mismatch"))).forEach {
            assertTrue(it.toString(), shouldTryNextRemoteAddress(it))
        }
        listOf(JSchException("Auth fail"), JSchException("Auth cancel"), CancellationException("cancelled"), InterruptedException(),
            hostKeyFailure(JSchUnknownHostKeyException::class.java, "untrusted"),
            RemoteConnectException("host", JSchException("Auth fail"), RemotePasswordSignInFailure.REJECTED, "user")).forEach {
            assertFalse(it.toString(), shouldTryNextRemoteAddress(it))
        }
    }

    @Test fun fallbackUsesProvidedOrderStopsAtSuccessAndReturnsFirstFailureIfAllFail() = runBlocking {
        val attempted = mutableListOf<String>()
        val first = ConnectException("first")
        val result = tryRemoteAddresses(listOf("last", "main", "alternate")) {
            attempted += it
            if (it == "last") throw first
            "session"
        }
        assertEquals("session", result)
        assertEquals(listOf("last", "main"), attempted)
        val failure = runCatching {
            tryRemoteAddresses(listOf("last", "main")) { if (it == "last") throw first else throw UnknownHostException("second") }
        }.exceptionOrNull()
        assertSame(first, failure)
    }

    @Test fun anotherMachinesKeyAtOneAddressIsReportedOnlyWhenNoAddressFailedOtherwise() = runBlocking {
        val changed = RemoteConnectException("lan", hostKeyFailure(JSchChangedHostKeyException::class.java, "mismatch"))
        val timeout = SocketTimeoutException("tailscale")
        assertSame(timeout, runCatching {
            tryRemoteAddresses(listOf("lan", "tailscale")) { if (it == "lan") throw changed else throw timeout }
        }.exceptionOrNull())
        assertEquals("session", tryRemoteAddresses(listOf("lan", "tailscale")) { if (it == "lan") throw changed else "session" })
        assertSame(changed, runCatching { tryRemoteAddresses(listOf("lan")) { throw changed } }.exceptionOrNull())
    }

    @Test fun refusedSignInUnconfirmedIdentityAndCancellationStopImmediatelyEvenAfterNetworkFailure() = runBlocking {
        listOf(hostKeyFailure(JSchUnknownHostKeyException::class.java, "untrusted"),
            RemoteConnectException("main", JSchException("Auth fail"), RemotePasswordSignInFailure.NOT_OFFERED, "user"),
            CancellationException("cancelled")).forEach { failure ->
            val attempted = mutableListOf<String>()
            val actual = runCatching {
                tryRemoteAddresses(listOf("last", "main", "alternate")) {
                    attempted += it
                    if (it == "last") throw ConnectException("refused") else throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertEquals(listOf("last", "main"), attempted)
        }
    }

    /** A proxy or VPN app on the phone answers for an address it cannot reach, then hangs up. */
    @Test fun anAddressThatAcceptsAndHangsUpMovesOnToTheNext() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                while (true) runCatching { server.accept().close() }.getOrElse { break }
            }
            val attempted = mutableListOf<Int>()
            val result = tryRemoteAddresses(listOf(server.localPort, 0)) { port ->
                attempted += port
                if (port == 0) "tailscale" else NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", port, 2_000).sha256Fingerprint
            }
            assertEquals("tailscale", result)
            assertEquals(listOf(server.localPort, 0), attempted)
            val failure = runCatching { NativeSshWorkspaceTransport.discoverHostKey("127.0.0.1", server.localPort, 2_000) }.exceptionOrNull()
            assertTrue(failure.toString(), failure != null && isRemoteNetworkFailure(failure))
        }
    }
}
