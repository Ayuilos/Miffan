package me.rerere.workspace

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableSocketFactoryTest {
    @Test fun cancelEndsABlockedReadAtOnce() {
        ServerSocket(0).use { server ->
            val factory = CancellableSocketFactory(2_000)
            val socket = factory.createSocket("127.0.0.1", server.localPort)
            // Never writes: the read below blocks, and ignores Thread.interrupt. Held open, or a garbage
            // collection may close it and the read sees the end of the stream instead of the cancel.
            server.accept().use {
                val read = CompletableFuture.supplyAsync {
                    runCatching { socket.getInputStream().read() }.exceptionOrNull()
                }
                Thread.sleep(200)
                val started = System.nanoTime()
                factory.cancel()
                val error = read.get(2, TimeUnit.SECONDS)
                assertTrue("read ended with $error", error is IOException)
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            }
        }
    }

    @Test fun attemptsAfterCancelFailAtOnce() {
        ServerSocket(0).use { server ->
            val factory = CancellableSocketFactory(2_000)
            factory.cancel()
            val error = runCatching { factory.createSocket("127.0.0.1", server.localPort) }.exceptionOrNull()
            assertTrue(error is IOException)
        }
    }
}
