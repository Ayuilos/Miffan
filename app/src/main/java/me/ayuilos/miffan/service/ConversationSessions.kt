package me.ayuilos.miffan.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Live conversation sessions by conversation id, observable through [jobs]. */
internal class ConversationSessions {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val version = MutableStateFlow(0L)

    val values: Collection<ConversationSession> get() = sessions.values
    val size: Int get() = sessions.size

    operator fun get(id: Uuid): ConversationSession? = sessions[id]

    operator fun contains(id: Uuid): Boolean = sessions.containsKey(id)

    fun getOrCreate(id: Uuid, create: (Uuid) -> ConversationSession): ConversationSession {
        var created: ConversationSession? = null
        val session = sessions.computeIfAbsent(id) { create(it).also { new -> created = new } }
        // Announce a session only once the map holds it: a collector resumed in place (Main.immediate)
        // re-reads the map right away and would otherwise miss the session until the next change.
        if (session === created) version.update { it + 1 }
        return session
    }

    fun remove(id: Uuid, session: ConversationSession): Boolean =
        sessions.remove(id, session).also { removed -> if (removed) version.update { it + 1 } }

    fun clear() {
        sessions.clear()
        version.update { it + 1 }
    }

    /** Generation jobs of the sessions that are generating. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun jobs(): Flow<Map<Uuid, Job?>> = version.flatMapLatest {
        val current = sessions.values.toList()
        if (current.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(current.map { session -> session.generationJob.map { job -> session.id to job } }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }
}
