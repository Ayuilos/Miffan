package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.cuaspace.SpaceInput
import me.rerere.cuaspace.SpaceMediaEvent
import me.rerere.cuaspace.SpaceVideoChannel
import me.rerere.cuaspace.SpaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuaSpaceDesktopSessionTest {
    private class Channel : SpaceVideoChannel {
        override val events: SharedFlow<SpaceMediaEvent> = MutableSharedFlow()
        private val batches = mutableListOf<List<SpaceInput>>()
        val sent: List<List<SpaceInput>> get() = synchronized(batches) { batches.toList() }
        override fun send(events: List<SpaceInput>): Boolean { synchronized(batches) { batches += events }; return true }
        override fun requestKeyframe() {}
        override fun ack(sequence: Long) {}
        override suspend fun closeSession() {}
    }

    private val window = SpaceWindow(id = "w", epoch = 1, app = "Safari", appId = "", pid = 7, title = "Docs", x = 0.0, y = 0.0,
        width = 800.0, height = 600.0, state = SpaceWindow.State.NORMAL, available = true, limitation = null, focused = true, zOrder = 0)

    /** A session on the test's clock and dispatcher; [raise] and [front] stand in for the computer. */
    private class Rig(
        test: TestScope,
        window: SpaceWindow?,
        raise: suspend (SpaceWindow) -> Unit = {},
        front: (suspend () -> Boolean?)? = null,
    ) {
        var now = 1_000_000L
        var raises = 0
        val channel = Channel()
        val dropped = mutableListOf<Unit>()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val session = CuaSpaceDesktopSession(null, channel, "test", CoroutineScope(SupervisorJob() + dispatcher), window = window,
            raise = { raises++; raise(it) }, frontCheck = front, clock = { now }, io = dispatcher)

        // Not the test's background scope: advanceUntilIdle does not wait for work there.
        init { CoroutineScope(SupervisorJob() + dispatcher).launch { session.inputDropped.collect { dropped += it } } }

        fun key(name: Char) = session.typeText(name.toString())
    }

    private fun List<List<SpaceInput>>.texts() = flatten().map { (it as SpaceInput.Text).text }

    @Test fun `input held during a raise goes out after it, in order`() = runTest {
        val rig = Rig(this, window, raise = { delay(500) })
        rig.key('a')
        rig.key('b')
        runCurrent()
        assertTrue(rig.channel.sent.isEmpty())
        rig.key('c')
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), rig.channel.sent.texts())
        assertEquals(1, rig.raises)
        // In front now: the next input goes straight out.
        rig.now += 100
        rig.key('d')
        assertEquals(listOf("a", "b", "c", "d"), rig.channel.sent.texts())
        assertEquals(1, rig.raises)
    }

    @Test fun `a failed raise sends only when the window is seen in front`() = runTest {
        var inFront: Boolean? = true
        val rig = Rig(this, window, raise = { error("unverified") }, front = { inFront })
        rig.key('a')
        advanceUntilIdle()
        assertEquals(listOf("a"), rig.channel.sent.texts())

        rig.session.noteFront(false)
        rig.now += 10_000
        rig.session.noteFront(false)
        inFront = false
        rig.key('b')
        advanceUntilIdle()
        // Not in front: dropped, said once, and the next input tries again.
        assertEquals(listOf("a"), rig.channel.sent.texts())
        assertEquals(1, rig.dropped.size)
        inFront = null
        rig.key('c')
        advanceUntilIdle()
        assertEquals(listOf("a"), rig.channel.sent.texts())
        assertEquals(3, rig.raises)
        assertEquals(2, rig.dropped.size)
    }

    @Test fun `a raise that takes too long checks the front before sending`() = runTest {
        var asked = 0
        val rig = Rig(this, window, raise = { awaitCancellation() }, front = { asked++; true })
        rig.key('a')
        advanceUntilIdle()
        assertEquals(1, asked)
        assertEquals(listOf("a"), rig.channel.sent.texts())
    }

    @Test fun `without a way to check the front a failed raise sends nothing`() = runTest {
        val rig = Rig(this, window, raise = { error("gone") })
        rig.key('a')
        advanceUntilIdle()
        assertTrue(rig.channel.sent.isEmpty())
        assertEquals(1, rig.dropped.size)
    }

    @Test fun `what the computer says right after a raise is ignored`() = runTest {
        val rig = Rig(this, window)
        rig.key('a')
        advanceUntilIdle()
        // Within the settle time the check may predate the raise.
        rig.now += 1_000
        rig.session.noteFront(false)
        rig.key('b')
        assertEquals(1, rig.raises)
        // Later it counts: the next input raises first.
        rig.now += 1_000
        rig.session.noteFront(false)
        rig.key('c')
        advanceUntilIdle()
        assertEquals(2, rig.raises)
        assertEquals(listOf("a", "b", "c"), rig.channel.sent.texts())
    }

    @Test fun `input after a pause with no recent check raises again`() = runTest {
        val rig = Rig(this, window)
        rig.key('a')
        advanceUntilIdle()
        rig.now += 2_000
        rig.session.noteFront(true)
        rig.now += 4_000
        rig.key('b')
        assertEquals(1, rig.raises)
        // Over eight seconds idle and the last check older than five: unsure, so it raises.
        rig.now += 9_000
        rig.key('c')
        advanceUntilIdle()
        assertEquals(2, rig.raises)
        assertEquals(listOf("a", "b", "c"), rig.channel.sent.texts())
    }

    @Test fun `closing during a raise sends nothing`() = runTest {
        val rig = Rig(this, window, raise = { delay(500) })
        rig.key('a')
        runCurrent()
        rig.session.close()
        advanceUntilIdle()
        assertTrue(rig.channel.sent.isEmpty())
        assertTrue(!rig.session.typeText("b"))
    }

    @Test fun `a whole screen never raises`() = runTest {
        val rig = Rig(this, null)
        rig.key('a')
        assertEquals(listOf("a"), rig.channel.sent.texts())
        assertEquals(0, rig.raises)
    }
}
