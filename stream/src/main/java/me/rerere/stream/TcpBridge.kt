package me.rerere.stream

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** AF_UNIX socketpairs only: these are descriptors, never listening TCP ports. */
internal class TcpBridge(private val connector: StreamTcpConnector) : Closeable {
    private val closed = AtomicBoolean()
    private val threads = ConcurrentHashMap.newKeySet<Thread>()
    private val channels = ConcurrentHashMap<Int, Closeable>()
    fun open(port: Int): Int {
        check(!closed.get())
        val channel = connector.open(port)
        val pair = try { ParcelFileDescriptor.createSocketPair() } catch (e: Throwable) { channel.closeable.close(); throw e }
        val fd = pair[0].fd
        val input = ParcelFileDescriptor.AutoCloseInputStream(pair[1])
        val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(pair[1].fileDescriptor))
        val once = AtomicBoolean()
        lateinit var cleanup: Closeable
        cleanup = Closeable {
            if (once.compareAndSet(false, true)) {
                runCatching { channel.closeable.close() }
                runCatching { input.close() }; runCatching { output.close() }
                channels.remove(fd, cleanup)
            }
        }
        channels[fd] = cleanup
        if (closed.get()) { cleanup.close(); pair[0].close(); error("Bridge closed") }
        fun pump(name: String, action: () -> Unit) = Thread({
            try { action() } catch (_: Exception) {} finally { cleanup.close(); threads.remove(Thread.currentThread()) }
        }, name).apply { isDaemon = true; threads.add(this); start() }
        fun copy(source: java.io.InputStream, target: java.io.OutputStream) {
            val buffer = ByteArray(32768)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) return
                if (count == 0) continue
                target.write(buffer, 0, count); target.flush()
            }
        }
        pump("StreamTcpRead") { copy(channel.input, output) }
        pump("StreamTcpWrite") { copy(input, channel.output) }
        return pair[0].detachFd()
    }
    fun closeChannel(fd: Int) { channels[fd]?.close() }
    fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        threads.toList().filter { it !== Thread.currentThread() }.forEach {
            it.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
        }
        return threads.isEmpty()
    }
    override fun close() { closed.set(true); channels.values.toList().forEach { it.close() } }
}
