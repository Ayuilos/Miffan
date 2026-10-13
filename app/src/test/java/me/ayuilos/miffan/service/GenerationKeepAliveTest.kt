package me.ayuilos.miffan.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationKeepAliveTest {
    private class Backend : GenerationKeepAliveBackend {
        val active = mutableSetOf<Uuid>()
        val workers = mutableSetOf<Uuid>()
        var allowed = true
        var starts = 0
        var expedited = 0
        override suspend fun acquireForeground(id: Uuid, conversationId: Uuid): Boolean {
            if (active.isNotEmpty()) { active += id; return true }
            starts++
            if (!allowed) return false
            active += id; return true
        }
        override suspend fun acquireExpedited(id: Uuid, conversationId: Uuid) { expedited++; workers += id }
        override suspend fun releaseForeground(id: Uuid) { active -= id }
        override suspend fun releaseExpedited(id: Uuid) { workers -= id }
    }

    @Test fun runningServiceReusesLeaseAndHandoffNeverDropsToZero() = runTest {
        val backend = Backend(); val keeper = GenerationKeepAlive(backend)
        val a = Uuid.random(); val b = Uuid.random(); val writeback = Uuid.random()
        keeper.acquire(a, a); backend.allowed = false
        keeper.acquire(b, b); keeper.acquire(b, b)
        keeper.release(a)
        assertEquals(setOf(b), backend.active); assertEquals(1, backend.starts)
        keeper.acquire(writeback, a); keeper.release(b)
        assertEquals(setOf(writeback), backend.active)
        keeper.release(writeback); keeper.release(writeback)
        assertTrue(backend.active.isEmpty()); assertEquals(0, backend.expedited)
    }

    @Test fun deniedForegroundUsesExpeditedWorkAndReleasesMatchingLease() = runTest {
        val backend = Backend().apply { allowed = false }; val keeper = GenerationKeepAlive(backend)
        val a = Uuid.random(); val b = Uuid.random()
        assertTrue(keeper.acquire(a, a)); keeper.acquire(b, b)
        assertEquals(setOf(a, b), backend.workers)
        keeper.release(a); assertEquals(setOf(b), backend.workers)
        keeper.release(b); assertTrue(backend.workers.isEmpty())
        assertEquals(2, backend.expedited)
    }

    @Test fun workerMustStartBeforeGenerationMayContinue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = object : GenerationKeepAliveBackend {
            override suspend fun acquireForeground(id: Uuid, conversationId: Uuid) = false
            override suspend fun acquireExpedited(id: Uuid, conversationId: Uuid) { gate.await() }
            override suspend fun releaseForeground(id: Uuid) = Unit
            override suspend fun releaseExpedited(id: Uuid) = Unit
        }
        val keeper = GenerationKeepAlive(backend); val token = Uuid.random()
        val lease = async { keeper.acquire(token, token) }
        runCurrent(); assertFalse(lease.isCompleted)
        gate.complete(Unit); assertTrue(lease.await()); keeper.release(token)
    }
}
