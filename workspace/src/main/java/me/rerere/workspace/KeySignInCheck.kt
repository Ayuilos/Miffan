package me.rerere.workspace

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import java.util.concurrent.CancellationException

enum class KeySignInCheck { ACCEPTED, REFUSED, UNREACHABLE }

/** Authentication refusal only, never a changed identity, cancellation, or local key error. */
fun isRemoteKeyAuthenticationRefused(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(32).toList()
    if (causes.any { it is CancellationException || it is InterruptedException ||
            it is JSchHostKeyException || it is JSchUnknownHostKeyException }) return false
    return causes.any { it is JSchException &&
        (it.message.orEmpty().startsWith("Auth fail") || it.message.orEmpty().startsWith("Auth cancel")) }
}
