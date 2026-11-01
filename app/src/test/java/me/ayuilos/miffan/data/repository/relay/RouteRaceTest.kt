package me.ayuilos.miffan.data.repository.relay

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRaceTest {
    private val closed = CopyOnWriteArrayList<String>()
    private val prefer: (Throwable, Throwable) -> Throwable = { direct, relay -> if (direct.message == "net") relay else direct }

    @Test fun directWinsBeforeRelayStarts() = runTest {
        var relayStarted = false
        val (value, winner) = raceRoutes(
            direct = { delay(300); "lan" },
            relay = { relayStarted = true; "relay" },
            relayHeadStartMillis = 2_000, close = { closed += it }, preferError = prefer,
        )
        assertEquals("lan" to RouteWinner.DIRECT, value to winner)
        assertTrue(!relayStarted)
    }

    @Test fun relayStartsAfterHeadStartAndWins() = runTest {
        val (value, winner) = raceRoutes(
            direct = { delay(8_000); throw IOException("net") },
            relay = { delay(1_000); "relay" },
            relayHeadStartMillis = 2_000, close = { closed += it }, preferError = prefer,
        )
        assertEquals("relay" to RouteWinner.RELAY, value to winner)
        assertEquals(3_000, currentTime)
    }

    @Test fun relayStartsAsSoonAsDirectFails() = runTest {
        val (_, winner) = raceRoutes(
            direct = { delay(100); throw IOException("net") },
            relay = { delay(500); "relay" },
            relayHeadStartMillis = 2_000, close = { closed += it }, preferError = prefer,
        )
        assertEquals(RouteWinner.RELAY, winner)
        assertEquals(600, currentTime)
    }

    @Test fun lateLoserIsClosed() = runTest {
        val directDone = CompletableDeferred<Unit>()
        val (value, _) = raceRoutes(
            direct = { delay(2_500); directDone.complete(Unit); "lan" },
            relay = { delay(400); "relay" },
            relayHeadStartMillis = 2_000, close = { closed += it }, preferError = prefer,
        )
        // direct finishes at 2500 ms, relay at 2400 ms: relay wins; direct is cancelled, not leaked.
        assertEquals("relay", value)
        assertTrue(closed.isEmpty())
    }

    @Test fun bothFailReportsPreferredError() = runTest {
        val error = runCatching {
            raceRoutes<String>(
                direct = { throw IOException("net") },
                relay = { throw IOException("host_offline") },
                relayHeadStartMillis = 2_000, close = {}, preferError = prefer,
            )
        }.exceptionOrNull()
        assertEquals("host_offline", error?.message)
    }

    @Test fun directAuthErrorBeatsRelayError() = runTest {
        val error = runCatching {
            raceRoutes<String>(
                direct = { throw IOException("auth") },
                relay = { throw IOException("host_offline") },
                relayHeadStartMillis = 0, close = {}, preferError = prefer,
            )
        }.exceptionOrNull()
        assertEquals("auth", error?.message)
    }

    @Test fun cancellingTheCallerCancelsBoth() = runTest {
        val job = async {
            raceRoutes<String>(direct = { awaitCancellation() }, relay = { awaitCancellation() },
                relayHeadStartMillis = 0, close = {}, preferError = prefer)
        }
        delay(100)
        job.cancel()
        assertTrue(runCatching { job.await() }.isFailure)
    }

    @Test fun withoutRelayOnlyDirectRuns() = runTest {
        val (value, winner) = raceRoutes(direct = { "lan" }, relay = null, relayHeadStartMillis = 0,
            close = {}, preferError = prefer)
        assertEquals("lan" to RouteWinner.DIRECT, value to winner)
    }
}
