package me.rerere.workspace

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
            RemoteConnectException("host", JSchException("java.net.ConnectException: Connection refused"))).forEach {
            assertTrue(it.toString(), shouldTryNextRemoteAddress(it))
        }
        listOf(JSchException("Auth fail"), JSchException("Auth cancel"), JSchException("channel is closed"),
            JSchException("Session.connect: timeout during authentication", SocketTimeoutException()),
            IllegalArgumentException("configuration"), CancellationException("cancelled"),
            hostKeyFailure(JSchChangedHostKeyException::class.java, "connection timed out"), hostKeyFailure(JSchUnknownHostKeyException::class.java, "connection refused"),
            RemoteConnectException("host", hostKeyFailure(JSchChangedHostKeyException::class.java, "mismatch")),
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

    @Test fun identityAuthenticationAndCancellationStopImmediatelyEvenAfterNetworkFailure() = runBlocking {
        listOf(hostKeyFailure(JSchChangedHostKeyException::class.java, "mismatch"), hostKeyFailure(JSchUnknownHostKeyException::class.java, "untrusted"),
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
}
