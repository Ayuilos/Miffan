package me.rerere.workspace

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Whether [error] means the server was never reached. A proxy or VPN app on the phone may accept
 * the connection itself and close it once its own dial fails, so an early close counts too.
 */
fun isRemoteNetworkFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(32).toList()
    if (causes.any { it is CancellationException || it is InterruptedException || it is JSchHostKeyException ||
            (it is RemoteConnectException && it.passwordSignIn != null) ||
            (it is JSchException && it.message.orEmpty().contains("auth", ignoreCase = true)) }) return false
    return causes.any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is SocketTimeoutException || ((it is JSchException || it is SocketException) &&
            it.message.orEmpty().lowercase(Locale.ROOT).let { message ->
                listOf("connection refused", "connect timed out", "connection timed out", "timeout: socket is not established",
                    "no route to host", "network is unreachable", "network unreachable", "unknownhostexception",
                    "connection is closed by foreign host", "connection reset", "end of io stream read")
                    .any(message::contains)
            })
    }
}

/**
 * Any failure at one address of a pinned computer may try the next one, since the user's network
 * decides which address works. Only a refused sign-in (the pinned computer itself answered) and a
 * computer with no confirmed identity, which every address shares, stop the attempt.
 */
fun shouldTryNextRemoteAddress(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(32).toList()
    return causes.none { it is CancellationException || it is InterruptedException || it is JSchUnknownHostKeyException ||
        (it is RemoteConnectException && it.passwordSignIn != null) || isRemoteKeyAuthenticationRefused(it) }
}

private fun Throwable.isHostKeyFailure(): Boolean =
    generateSequence(this) { it.cause }.take(32).any { it is JSchHostKeyException }

/**
 * Keeps the original exception object, including identity and password-sign-in details. When every
 * address fails, a different key at one of them (another machine on today's network reusing a LAN
 * address) is reported only if no address failed some other way.
 */
suspend fun <A, T> tryRemoteAddresses(addresses: List<A>, connect: suspend (A) -> T): T {
    require(addresses.isNotEmpty())
    val failures = mutableListOf<Exception>()
    for (address in addresses) {
        try {
            return connect(address)
        } catch (error: Exception) {
            if (!shouldTryNextRemoteAddress(error)) throw error
            failures += error
        }
    }
    throw failures.firstOrNull { !it.isHostKeyFailure() } ?: failures.first()
}
