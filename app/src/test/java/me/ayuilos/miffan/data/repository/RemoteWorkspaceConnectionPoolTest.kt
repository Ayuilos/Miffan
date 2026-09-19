package me.ayuilos.miffan.data.repository

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkspaceConnectionPoolTest {
    private val identity = RemoteConnectionIdentity("workspace", "host", "revision-1", "/project")

    private class FakeConnection : Closeable {
        @Volatile var open = true
        override fun close() { open = false }
    }

    @Test(timeout = 10_000) fun concurrentWaitersShareOneConnectionAndIdleStartsAfterLastLease() = runBlocking {
        val opens = AtomicInteger()
        val pool = RemoteWorkspaceConnectionPool<FakeConnection>(
            connected = { it.open },
            idleMillis = 100,
            heartbeatMillis = 60_000,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val first = async { pool.acquire(identity) { opens.incrementAndGet(); FakeConnection() } }
        val second = async { pool.acquire(identity) { opens.incrementAndGet(); FakeConnection() } }
        val lease1 = first.await()
        val lease2 = second.await()
        assertEquals(1, opens.get())
        assertTrue(lease1.session === lease2.session)
        lease1.close()
        delay(160)
        assertTrue(lease2.session.open)
        lease2.close()
        delay(160)
        assertFalse(lease2.session.open)
        assertEquals(RemoteConnectionStatus.DISCONNECTED, pool.states.value[identity.workspaceId])
    }

    @Test(timeout = 10_000) fun cancellingOneConnectWaiterLeavesTheOtherAlive() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val pool = RemoteWorkspaceConnectionPool<FakeConnection>(
            connected = { it.open },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val first = async { pool.acquire(identity) { opens.incrementAndGet(); gate.await(); FakeConnection() } }
        val second = async { pool.acquire(identity) { opens.incrementAndGet(); gate.await(); FakeConnection() } }
        while (opens.get() == 0) delay(1)
        first.cancel()
        gate.complete(Unit)
        val survivor = second.await()
        assertEquals(1, opens.get())
        assertTrue(survivor.session.open)
        survivor.close()
    }

    @Test(timeout = 10_000) fun manualDisconnectWinsAgainstLateConnectionAndSuppressesPagePreconnect() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val made = CompletableDeferred<FakeConnection>()
        val pool = RemoteWorkspaceConnectionPool<FakeConnection>(
            connected = { it.open },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val waiting = async {
            runCatching {
                pool.acquire(identity) {
                    started.complete(Unit)
                    withContext(NonCancellable) { gate.await() }
                    FakeConnection().also(made::complete)
                }
            }
        }
        started.await()
        pool.disconnect(identity.workspaceId)
        assertFalse(pool.mayPreconnect(identity.workspaceId))
        gate.complete(Unit)
        assertTrue(waiting.await().isFailure)
        val stale = made.await()
        delay(20)
        assertFalse(stale.open)
        assertEquals(RemoteConnectionStatus.DISCONNECTED, pool.states.value[identity.workspaceId])
        pool.acquire(identity) { FakeConnection() }.close()
        assertTrue(pool.mayPreconnect(identity.workspaceId))
    }

    @Test(timeout = 10_000) fun changedTargetClosesOldLeaseAndOpensNewIdentity() = runBlocking {
        val pool = RemoteWorkspaceConnectionPool<FakeConnection>(
            connected = { it.open },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val original = pool.acquire(identity) { FakeConnection() }
        val revised = pool.acquire(identity.copy(connectionRevision = "revision-2")) { FakeConnection() }
        assertFalse(original.session.open)
        assertTrue(revised.session.open)
        original.close()
        revised.close()
    }

    @Test(timeout = 10_000) fun staleFailureCannotCloseReplacementConnection() = runBlocking {
        val pool = RemoteWorkspaceConnectionPool<FakeConnection>(
            connected = { it.open },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val old = pool.acquire(identity) { FakeConnection() }
        val replacement = pool.acquire(identity.copy(connectionRevision = "revision-2")) {
            FakeConnection()
        }
        pool.connectionLost(identity.workspaceId, old.session)
        assertTrue(replacement.session.open)
        assertEquals(RemoteConnectionStatus.CONNECTED, pool.states.value[identity.workspaceId])
        replacement.close()
    }
}
