package me.ayuilos.miffan.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotPacingTest {
    private val relay = SnapshotPacing(intervalMillis = 2_000, idleMillis = 5_000, stopAfterStillMillis = 30_000)

    @Test fun refreshesQuicklyAfterAChange() {
        assertEquals(2_000L, relay.nextWait(unchanged = 0, stillForMillis = 0))
        assertEquals(2_000L, relay.nextWait(unchanged = 2, stillForMillis = 4_000))
    }

    @Test fun slowsDownWhileStill() {
        assertEquals(5_000L, relay.nextWait(unchanged = 3, stillForMillis = 6_000))
        assertEquals(5_000L, relay.nextWait(unchanged = 6, stillForMillis = 29_999))
    }

    @Test fun stopsAfterThirtyStillSeconds() {
        assertNull(relay.nextWait(unchanged = 7, stillForMillis = 30_000))
    }

    @Test fun aNewPictureNeverStops() {
        // The capture that just changed resets "unchanged", whatever the clock says.
        assertEquals(2_000L, relay.nextWait(unchanged = 0, stillForMillis = 60_000))
    }

    @Test fun withoutALimitItNeverStops() {
        val open = SnapshotPacing(2_000, 5_000, stopAfterStillMillis = null)
        assertEquals(5_000L, open.nextWait(unchanged = 100, stillForMillis = 3_600_000))
    }

    /** Polls a still screen with the session's rules: about 10 captures, then none. */
    @Test fun aStillScreenCostsAboutTenCapturesThenNone() {
        var clock = 0L
        var unchanged = 0
        var captures = 0
        while (clock < 10 * 60_000) {
            captures++
            unchanged++ // nothing ever changes; the first picture counts as the change at t=0
            val wait = relay.nextWait(if (captures == 1) 0 else unchanged, clock) ?: break
            clock += wait
        }
        assertEquals(true, captures in 8..12)
        assertEquals(true, clock in 30_000..36_000)
    }
}
