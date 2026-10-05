package me.ayuilos.miffan.data.thread

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import me.ayuilos.miffan.data.db.dao.ThreadSegmentStamp
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/** Loads the segments (conversations) that make up an assistant's IM thread. */
class ThreadRepository(private val conversationRepository: ConversationRepository) {
    /**
     * Full segments of [assistantId], newest first. Unchanged segments are reused between
     * emissions so a save in one segment does not reload every message of the others.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSegments(assistantId: Uuid, limit: Int): Flow<List<Conversation>> {
        val cache = HashMap<String, Pair<ThreadSegmentStamp, Conversation>>()
        return conversationRepository.observeThreadSegmentStamps(assistantId, limit).mapLatest { stamps ->
            val loaded = stamps.mapNotNull { stamp ->
                val cached = cache[stamp.id]?.takeIf { it.first == stamp }?.second
                (cached ?: conversationRepository.getConversationById(Uuid.parse(stamp.id)))
                    ?.let { stamp to it }
            }
            cache.clear()
            loaded.forEach { (stamp, conversation) -> cache[stamp.id] = stamp to conversation }
            loaded.map { it.second }
        }
    }
}
