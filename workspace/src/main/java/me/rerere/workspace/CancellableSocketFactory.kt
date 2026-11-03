package me.rerere.workspace

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/**
 * Plain TCP sockets for JSch that [cancel] can close from another thread. A blocking
 * `Socket.connect` or read ignores `Thread.interrupt`; closing the socket ends it at once, so a
 * cancelled connection attempt (for example the direct route after the relay won) stops now
 * instead of at its connect timeout.
 *
 * JSch applies no connect timeout to a custom factory's sockets, so [connectTimeoutMillis] does.
 */
class CancellableSocketFactory(private val connectTimeoutMillis: Int) : com.jcraft.jsch.SocketFactory {
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var cancelled = false

    init {
        require(connectTimeoutMillis > 0)
    }

    override fun createSocket(host: String, port: Int): Socket {
        if (cancelled) throw IOException("Connection attempt cancelled")
        val socket = Socket()
        sockets += socket
        // A cancel() between the check above and registering must still close this socket.
        if (cancelled) socket.close()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            sockets -= socket
            if (cancelled) throw IOException("Connection attempt cancelled", error)
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    /** Closes every socket this factory made; later attempts fail at once. */
    fun cancel() {
        cancelled = true
        val open = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        open.forEach { runCatching { it.close() } }
    }
}
