package me.rerere.cuaspace

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** A byte stream to a port on the Space host's loopback interface, for example an SSH direct-tcpip channel. */
interface SpaceStream : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    val isConnected: Boolean
}

fun interface SpaceStreamOpener {
    /** Opens a stream to [port] on the host's loopback interface. */
    fun open(port: Int, timeoutMillis: Int): SpaceStream
}

/**
 * Hands OkHttp sockets whose `connect` opens a [SpaceStream] instead of a TCP connection, so nothing
 * listens on the device. The address OkHttp passes is ignored apart from its port.
 */
class ChannelSocketFactory(private val opener: SpaceStreamOpener) : SocketFactory() {
    override fun createSocket(): Socket = ChannelSocket(opener)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)
}

private class ChannelSocket(private val opener: SpaceStreamOpener) : Socket() {
    @Volatile private var stream: SpaceStream? = null
    @Volatile private var remote: InetSocketAddress? = null
    @Volatile private var closed = false

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val address = endpoint as? InetSocketAddress ?: throw IOException("Unsupported address $endpoint")
        if (closed) throw IOException("Socket is closed")
        remote = address
        stream = try {
            opener.open(address.port, if (timeout > 0) timeout else DEFAULT_TIMEOUT_MILLIS)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Could not open the Space channel", error)
        }
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun getInputStream(): InputStream = stream?.input ?: throw IOException("Socket is not connected")
    override fun getOutputStream(): OutputStream = stream?.output ?: throw IOException("Socket is not connected")
    override fun isConnected(): Boolean = stream != null
    override fun isClosed(): Boolean = closed
    override fun getInetAddress(): InetAddress? = remote?.address
    override fun getPort(): Int = remote?.port ?: 0
    override fun getRemoteSocketAddress(): SocketAddress? = remote

    // Channel streams have no read timeouts or half-close; OkHttp's own timeouts still apply.
    override fun setSoTimeout(timeout: Int) = Unit
    override fun shutdownInput() = Unit
    override fun shutdownOutput() = Unit
    override fun isInputShutdown(): Boolean = closed
    override fun isOutputShutdown(): Boolean = closed

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stream?.close()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
    }
}
