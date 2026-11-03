package me.ayuilos.miffan.data.repository.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectorUpdatesTest {
    @Test
    fun `a connector is outdated only when both versions are known and differ`() {
        assertTrue(RelayConnectorStatus(installed = true, version = "1.1.0", pinnedVersion = "1.2.0").outdated)
        assertFalse(RelayConnectorStatus(installed = true, version = "1.2.0", pinnedVersion = "1.2.0").outdated)
        assertFalse(RelayConnectorStatus(installed = true, version = "", pinnedVersion = "1.2.0").outdated)
        assertFalse(RelayConnectorStatus(installed = true, version = "1.1.0", pinnedVersion = "").outdated)
        assertFalse(RelayConnectorStatus(installed = false).outdated)
    }

    @Test
    fun `each computer is looked at once per run, and a failure stays quiet`() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val upgraded = mutableListOf<String>()
        val scope = TestScope(testScheduler)
        RelayConnectorUpdates(events,
            workspaceOf = { host -> if (host == "no-workspace") null else "ws-$host" },
            upgrade = { workspace -> if (workspace == "ws-broken") error("download failed"); upgraded += workspace; true },
            scope = scope, dispatcher = UnconfinedTestDispatcher(testScheduler), log = {})
        scope.advanceUntilIdle()
        for (host in listOf("mac", "mac", "broken", "broken", "no-workspace", "linux", "mac")) {
            events.emit(host)
            scope.advanceUntilIdle()
        }
        assertEquals(listOf("ws-mac", "ws-linux"), upgraded)
    }
}
