package me.ayuilos.miffan.data.repository

import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A verified SSH session is scoped to one workspace and one immutable target revision. */
internal data class RemoteConnectionIdentity(
    val workspaceId: String,
    val hostId: String,
    val connectionRevision: String,
    val remotePath: String,
)

enum class RemoteConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/**
 * Process-local owner of SSH sessions. A lease protects an operation or a visible page from idle
 * eviction. Invalidating a target closes even leased sessions, so old credentials cannot be used.
 */
internal class RemoteWorkspaceConnectionPool<T : Closeable>(
    private val connected: (T) -> Boolean,
    private val idleMillis: Long = 5 * 60_000L,
    private val heartbeatMillis: Long = 10_000L,
    private val nowNanos: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val heartbeatSleep: suspend (Long) -> Unit = { delay(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    internal class Entry<T>(val identity: RemoteConnectionIdentity) {
        val ready = CompletableDeferred<T>()
        var session: T? = null
        var references = 0
        var openJob: Job? = null
        var idleJob: Job? = null
        var idleGeneration = 0L
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry<T>>()
    private val _states = MutableStateFlow<Map<String, RemoteConnectionStatus>>(emptyMap())
    val states = _states.asStateFlow()
    private val previouslyConnected = mutableSetOf<String>()
    private val manuallyDisconnected = mutableSetOf<String>()

    init {
        scope.launch {
            while (true) {
                heartbeatSleep(heartbeatMillis)
                synchronized(lock) {
                    entries.values.toList().forEach { entry ->
                        if (entry.session?.let { !connected(it) } == true) {
                            evict(entry, RemoteConnectionStatus.FAILED)
                        }
                    }
                }
            }
        }
    }

    inner class Lease internal constructor(private val entry: Entry<T>, val session: T) : Closeable {
        private var closed = false
        override fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            if (entries[entry.identity.workspaceId] !== entry) return@synchronized
            entry.references--
            if (entry.references == 0) scheduleIdle(entry)
        }
    }

    /** Acquiring a lease is an explicit connect request, including after a manual disconnect. */
    suspend fun acquire(identity: RemoteConnectionIdentity, explicit: Boolean = true,
        open: suspend () -> T): Lease {
        val entry: Entry<T>
        synchronized(lock) {
            if (explicit) manuallyDisconnected.remove(identity.workspaceId)
            else check(identity.workspaceId !in manuallyDisconnected) {
                "Remote workspace was manually disconnected"
            }
            val current = entries[identity.workspaceId]
            if (current != null && (current.identity != identity ||
                    current.session?.let { !connected(it) } == true)) {
                evict(current, RemoteConnectionStatus.DISCONNECTED)
            }
            entry = entries[identity.workspaceId] ?: Entry<T>(identity).also { fresh ->
                entries[identity.workspaceId] = fresh
                publish(identity.workspaceId, if (identity.workspaceId in previouslyConnected)
                    RemoteConnectionStatus.RECONNECTING else RemoteConnectionStatus.CONNECTING)
                fresh.openJob = scope.launch {
                    var opened: T? = null
                    try {
                        opened = open()
                        synchronized(lock) {
                            if (entries[identity.workspaceId] === fresh) {
                                fresh.session = opened
                                previouslyConnected += identity.workspaceId
                                publish(identity.workspaceId, RemoteConnectionStatus.CONNECTED)
                                fresh.ready.complete(opened)
                                opened = null
                            }
                        }
                    } catch (error: Throwable) {
                        synchronized(lock) {
                            if (entries[identity.workspaceId] === fresh) {
                                entries.remove(identity.workspaceId)
                                publish(identity.workspaceId, RemoteConnectionStatus.FAILED)
                                fresh.ready.completeExceptionally(error)
                            }
                        }
                    } finally {
                        opened?.close()
                    }
                }
            }
            entry.idleJob?.cancel()
            entry.idleGeneration++
            entry.idleJob = null
            entry.references++
        }
        try {
            val session = entry.ready.await()
            synchronized(lock) {
                check(entries[identity.workspaceId] === entry && connected(session)) {
                    "Remote workspace connection changed"
                }
            }
            return Lease(entry, session)
        } catch (error: Throwable) {
            releasePending(entry)
            throw error
        }
    }

    /** Visible pages do not reconnect automatically after an explicit disconnect. */
    fun mayPreconnect(workspaceId: String): Boolean = synchronized(lock) {
        workspaceId !in manuallyDisconnected
    }

    fun disconnect(workspaceId: String) = synchronized(lock) {
        manuallyDisconnected += workspaceId
        entries[workspaceId]?.let { evict(it, RemoteConnectionStatus.DISCONNECTED) }
        publish(workspaceId, RemoteConnectionStatus.DISCONNECTED)
    }

    fun invalidate(workspaceId: String) = synchronized(lock) {
        manuallyDisconnected.remove(workspaceId)
        previouslyConnected.remove(workspaceId)
        entries[workspaceId]?.let { evict(it, RemoteConnectionStatus.DISCONNECTED) }
        publish(workspaceId, RemoteConnectionStatus.DISCONNECTED)
    }

    /** A failed operation may only retire the exact session on which it ran. */
    fun connectionLost(workspaceId: String, session: T) = synchronized(lock) {
        entries[workspaceId]?.takeIf { it.session === session }?.let {
            evict(it, RemoteConnectionStatus.FAILED)
        }
    }

    fun forget(workspaceId: String) = synchronized(lock) {
        entries[workspaceId]?.let { evict(it, RemoteConnectionStatus.DISCONNECTED) }
        manuallyDisconnected.remove(workspaceId)
        previouslyConnected.remove(workspaceId)
        _states.value = _states.value - workspaceId
    }

    private fun releasePending(entry: Entry<T>) = synchronized(lock) {
        if (entries[entry.identity.workspaceId] !== entry) return@synchronized
        entry.references--
        if (entry.references == 0) {
            if (entry.session == null) evict(entry, RemoteConnectionStatus.DISCONNECTED)
            else scheduleIdle(entry)
        }
    }

    private fun scheduleIdle(entry: Entry<T>) {
        entry.idleJob?.cancel()
        val generation = ++entry.idleGeneration
        val deadline = nowNanos() + TimeUnit.MILLISECONDS.toNanos(idleMillis)
        entry.idleJob = scope.launch {
            while (true) {
                val remaining = deadline - nowNanos()
                if (remaining <= 0) break
                sleep(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            }
            synchronized(lock) {
                if (entries[entry.identity.workspaceId] === entry && entry.references == 0 &&
                    entry.idleGeneration == generation) {
                    evict(entry, RemoteConnectionStatus.DISCONNECTED)
                }
            }
        }
    }

    private fun evict(entry: Entry<T>, status: RemoteConnectionStatus) {
        if (entries.remove(entry.identity.workspaceId, entry)) {
            entry.idleJob?.cancel()
            entry.openJob?.cancel()
            entry.ready.completeExceptionally(CancellationException("Remote workspace connection closed"))
            entry.session?.close()
            publish(entry.identity.workspaceId, status)
        }
    }

    private fun publish(workspaceId: String, status: RemoteConnectionStatus) {
        _states.value = _states.value + (workspaceId to status)
    }
}
