package me.ayuilos.miffan.data.thread

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Identifies the stored messages of a conversation: equal stamps mean nothing in them changed. */
data class ThreadStamp(val updateAt: Long, val nodeCount: Int, val revisionSum: Long, val zoneId: String = "")

/**
 * Keeps each conversation's [ThreadListDigest] until its [ThreadStamp] changes, so the Chats list reads and
 * decodes messages only for conversations that changed. A new read time never needs a read: the digest
 * holds what [ThreadListDigest.summary] needs.
 */
class ThreadDigestCache(private val load: suspend (Uuid) -> ThreadListDigest) {
    private class Entry(val stamp: ThreadStamp, val digest: ThreadListDigest)

    private val entries = ConcurrentHashMap<Uuid, Entry>()

    suspend fun digest(conversationId: Uuid, stamp: ThreadStamp): ThreadListDigest {
        entries[conversationId]?.takeIf { it.stamp == stamp }?.let { return it.digest }
        return load(conversationId).also { entries[conversationId] = Entry(stamp, it) }
    }

    /** Forgets conversations that are no longer anyone's newest. */
    fun retainOnly(conversationIds: Set<Uuid>) {
        entries.keys.retainAll(conversationIds)
    }
}
