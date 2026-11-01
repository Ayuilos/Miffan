package me.rerere.workspace

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Relay R0 spike: JSch over an OkHttp WebSocket through the relay (protocol v0) to a connector.
 * Skipped unless RELAY_SPIKE_URL (ws:// or wss:// base), RELAY_DEV_TOKEN, RELAY_SPIKE_HOST (host id),
 * RELAY_SPIKE_USER and RELAY_SPIKE_KEY (private key file) are set. Prototype only; R1 replaces it.
 */
class RelaySpikeLiveTest {
    private val base = System.getenv("RELAY_SPIKE_URL")
    private val token = System.getenv("RELAY_DEV_TOKEN")
    private val hostId = System.getenv("RELAY_SPIKE_HOST")
    private val user = System.getenv("RELAY_SPIKE_USER")
    private val key = System.getenv("RELAY_SPIKE_KEY")

    @Test
    fun jschRunsOverTheRelay() {
        assumeTrue(listOf(base, token, hostId, user, key).all { !it.isNullOrBlank() })
        val client = OkHttpClient.Builder().build()
        val jsch = JSch().apply { addIdentity(key) }
        val session = jsch.getSession(user, "relay", 22)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): Socket =
                RelaySocket.open(client, "$base/v0/connect/$hostId?ch=control", token)
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        val t0 = System.nanoTime()
        session.connect(20_000)
        val connectMs = (System.nanoTime() - t0) / 1_000_000
        try {
            val out = ByteArrayOutputStream()
            val exec = session.openChannel("exec") as ChannelExec
            exec.setCommand("echo relay-ok")
            exec.outputStream = out
            exec.connect(10_000)
            while (!exec.isClosed) Thread.sleep(20)
            assertEquals("relay-ok", out.toString().trim())

            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)
            val payload = ByteArray(2 shl 20) { (it % 251).toByte() }
            val t1 = System.nanoTime()
            sftp.put(payload.inputStream(), "relay-spike.bin")
            val back = sftp.get("relay-spike.bin").readBytes()
            val sftpMs = (System.nanoTime() - t1) / 1_000_000
            sftp.rm("relay-spike.bin")
            sftp.disconnect()
            assertTrue(payload.contentEquals(back))
            println("relay spike: ssh connect ${connectMs} ms, 2 MiB sftp put+get ${sftpMs} ms")
        } finally {
            session.disconnect()
            client.dispatcher.executorService.shutdown()
        }
    }
}

/** A Socket whose streams are a relay WebSocket session; the relay's "ready" text frame completes open. */
private class RelaySocket private constructor() : Socket() {
    private val incoming = ArrayBlockingQueue<ByteString>(256)
    private val ready = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    @Volatile private var closed = false
    private lateinit var ws: WebSocket

    private val input = object : InputStream() {
        private var current: ByteString? = null
        private var offset = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (current == null || offset >= current!!.size) {
                val next = incoming.poll(100, TimeUnit.MILLISECONDS)
                if (next != null) {
                    if (next.size == 0) return -1
                    current = next
                    offset = 0
                } else if (closed) return -1
            }
            val c = current!!
            val n = minOf(len, c.size - offset)
            c.substring(offset, offset + n).toByteArray().copyInto(b, off)
            offset += n
            return n
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("relay session closed")
            // OkHttp queues up to 16 MiB; wait instead of failing when SSH writes faster than the link.
            while (ws.queueSize() > 4L shl 20) {
                if (closed) throw IOException("relay session closed")
                Thread.sleep(5)
            }
            if (!ws.send(b.toByteString(off, len))) throw IOException("relay send failed")
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected(): Boolean = !closed
    override fun isClosed(): Boolean = closed
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun close() {
        if (closed) return
        closed = true
        ws.close(1000, null)
    }

    companion object {
        fun open(client: OkHttpClient, url: String, token: String): RelaySocket {
            val socket = RelaySocket()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            socket.ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"ready\"")) socket.ready.countDown()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    socket.incoming.put(bytes)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = finish(null)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    finish(IOException("relay: ${response?.code ?: ""} ${t.message}", t))
                private fun finish(error: Throwable?) {
                    socket.failure = error
                    socket.closed = true
                    socket.incoming.offer(ByteString.EMPTY)
                    socket.ready.countDown()
                }
            })
            if (!socket.ready.await(15, TimeUnit.SECONDS) || socket.closed) {
                socket.close()
                throw IOException("relay session did not become ready", socket.failure)
            }
            return socket
        }
    }
}
