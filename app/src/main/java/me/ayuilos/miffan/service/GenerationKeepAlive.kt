package me.ayuilos.miffan.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** One lease spans generation, persistence and handoff. The Android backend is replaceable in tests. */
internal interface GenerationKeepAliveBackend {
    suspend fun acquireForeground(id: Uuid, conversationId: Uuid): Boolean
    suspend fun acquireExpedited(id: Uuid, conversationId: Uuid)
    suspend fun releaseForeground(id: Uuid)
    suspend fun releaseExpedited(id: Uuid)
}

internal class GenerationKeepAlive(private val backend: GenerationKeepAliveBackend) {
    private val mutex = Mutex()
    private val leases = mutableMapOf<Uuid, Boolean>()

    suspend fun acquire(id: Uuid, conversationId: Uuid): Boolean = mutex.withLock {
        if (id in leases) return@withLock true
        val foreground = backend.acquireForeground(id, conversationId)
        if (!foreground) backend.acquireExpedited(id, conversationId)
        leases[id] = foreground
        true
    }

    suspend fun release(id: Uuid) = mutex.withLock {
        val foreground = leases.remove(id) ?: return@withLock
        if (foreground) backend.releaseForeground(id) else backend.releaseExpedited(id)
    }
}
