package me.ayuilos.miffan.data.thread

import me.ayuilos.miffan.data.model.Conversation
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Merges stored segments with the live copies of generating ones.
 *
 * A generation ends before the database emits its saved copy, so dropping the live copy right away
 * would make the finished reply vanish until the stored segment arrives. Each finished segment keeps
 * its last live copy until the stored one shows the same newest message, or for at most [holdMillis].
 */
class LiveSegmentHandoff private constructor(
    val merged: List<Conversation>,
    private val retained: Map<Uuid, Retained>,
    private val holdMillis: Long,
) {
    constructor(holdMillis: Long = DEFAULT_HOLD_MILLIS) : this(emptyList(), emptyMap(), holdMillis)

    private class Retained(val copy: Conversation, val since: Instant)

    fun next(stored: List<Conversation>?, live: List<Conversation>, now: Instant = Instant.now()): LiveSegmentHandoff {
        val storedById = stored.orEmpty().associateBy { it.id }
        val liveById = live.associateBy { it.id }
        val retained = buildMap {
            for ((id, kept) in retained) {
                if (id in liveById) continue
                val caughtUp = storedById[id]?.let { it.showsNewestOf(kept.copy) } == true
                if (!caughtUp && now.toEpochMilli() - kept.since.toEpochMilli() < holdMillis) put(id, kept)
            }
            // The newest live copy is what the user last saw; keep it from the moment it stops.
            for ((id, copy) in liveById) put(id, Retained(copy, now))
        }
        val shown = retained.mapValues { it.value.copy }
        val base = stored.orEmpty().map { shown[it.id] ?: it }
        val merged = base + shown.values.filter { copy -> base.none { it.id == copy.id } }
        return LiveSegmentHandoff(merged, retained, holdMillis)
    }

    private fun Conversation.showsNewestOf(live: Conversation): Boolean {
        val newest = live.currentMessages.lastOrNull() ?: return true
        val saved = currentMessages.lastOrNull { it.id == newest.id } ?: return false
        return saved.parts == newest.parts
    }

    companion object {
        const val DEFAULT_HOLD_MILLIS = 10_000L
    }
}
