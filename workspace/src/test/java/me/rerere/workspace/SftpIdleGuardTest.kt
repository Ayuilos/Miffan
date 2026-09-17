package me.rerere.workspace

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpIdleGuardTest {
    @Test fun progressingTransferCanOutliveItsIdleLimit() {
        val disconnected = CountDownLatch(1)
        SftpIdleGuard(500) { disconnected.countDown() }.run { monitor ->
            repeat(12) {
                assertTrue(monitor.count(4096))
                Thread.sleep(75)
            }
        }
        assertEquals(1L, disconnected.count)
        assertFalse(disconnected.await(600, TimeUnit.MILLISECONDS))
    }

    @Test fun timeoutDisconnectsAndPreservesUnderlyingFailure() {
        val disconnected = CountDownLatch(1)
        val original = IllegalStateException("pipe closed")
        val error = assertThrows(RemoteFileTimeoutException::class.java) {
            SftpIdleGuard(100) { disconnected.countDown() }.run {
                assertTrue(disconnected.await(2, TimeUnit.SECONDS))
                throw original
            }
        }
        assertEquals(original, error.cause)
    }

    @Test fun timeoutCannotBeReportedAsSuccessfulWrite() {
        val disconnected = CountDownLatch(1)
        assertThrows(RemoteFileTimeoutException::class.java) {
            SftpIdleGuard(100) { disconnected.countDown() }.run {
                assertTrue(disconnected.await(2, TimeUnit.SECONDS))
                "late result"
            }
        }
    }
}
