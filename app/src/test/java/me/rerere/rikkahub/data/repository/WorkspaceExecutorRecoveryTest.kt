package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceExecutorRecoveryTest {
    @Test
    fun rootfsRecoveryProbeRunsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var probeThread: Thread? = null

        val healthy = probeExecutorRootfsAfterFailure(IllegalStateException("install failed")) {
            probeThread = Thread.currentThread()
            true
        }

        assertTrue(healthy)
        assertNotSame(caller, probeThread)
    }

    @Test
    fun probeFailureIsSuppressedWithoutReplacingInstallFailure() = runBlocking {
        val installError = IllegalStateException("download checksum mismatch")
        val probeError = IllegalStateException("executor disconnected")

        val healthy = probeExecutorRootfsAfterFailure(installError) {
            throw probeError
        }

        assertFalse(healthy)
        assertEquals(probeError::class.java, installError.suppressed.single()::class.java)
        assertEquals("executor disconnected", installError.suppressed.single().message)
        assertEquals("download checksum mismatch", installError.message)
    }
}
