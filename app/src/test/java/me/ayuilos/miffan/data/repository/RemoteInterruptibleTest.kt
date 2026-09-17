package me.ayuilos.miffan.data.repository

import com.jcraft.jsch.JSchException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInterruptibleTest {
    @Test fun cancelledChannelHandshakeDoesNotBecomeConnectionFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        val job = launch(Dispatchers.Default) {
            try {
                runRemoteInterruptible {
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // Channel.sendChannelOpen swallows the interrupt and throws this instead.
                        throw JSchException("channel is not opened.")
                    }
                }
            } catch (error: Exception) {
                failure.set(error)
                throw error
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(failure.get() is CancellationException)
        } finally {
            job.cancel()
        }
    }

    @Test fun genuineChannelFailureIsPreservedWithoutRetry() = runBlocking {
        val original = JSchException("channel is not opened.")
        var attempts = 0
        val failure = try {
            runRemoteInterruptible { attempts++; throw original }
        } catch (error: Exception) {
            error
        }
        // Coroutine stack-trace recovery can copy exceptions across dispatcher boundaries.
        assertTrue(failure is JSchException)
        assertEquals(original.message, failure.message)
        assertEquals(1, attempts)
    }

    @Test fun successfulOperationReturnsItsResult() = runBlocking {
        assertEquals("files", runRemoteInterruptible { "files" })
    }
}
