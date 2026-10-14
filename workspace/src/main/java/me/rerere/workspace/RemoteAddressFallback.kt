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

/** Only failures to reach a server may try another address of the same pinned computer. */
fun shouldTryNextRemoteAddress(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(32).toList()
    if (causes.any { it is CancellationException || it is InterruptedException || it is JSchHostKeyException || it is JSchUnknownHostKeyException ||
            (it is RemoteConnectException && it.passwordSignIn != null) ||
            (it is JSchException && it.message.orEmpty().contains("auth", ignoreCase = true)) }) return false
    return causes.any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is SocketTimeoutException || ((it is JSchException || it is SocketException) &&
            it.message.orEmpty().lowercase(Locale.ROOT).let { message ->
                listOf("connection refused", "connect timed out", "connection timed out", "timeout: socket is not established",
                    "no route to host", "network is unreachable", "network unreachable", "unknownhostexception")
                    .any(message::contains)
            })
    }
}

/** Keeps the original exception object, including identity and password-sign-in details. */
suspend fun <A, T> tryRemoteAddresses(addresses: List<A>, connect: suspend (A) -> T): T {
    require(addresses.isNotEmpty())
    var firstFailure: Exception? = null
    for (address in addresses) {
        try {
            return connect(address)
        } catch (error: Exception) {
            if (!shouldTryNextRemoteAddress(error)) throw error
            if (firstFailure == null) firstFailure = error
        }
    }
    throw requireNotNull(firstFailure)
}
