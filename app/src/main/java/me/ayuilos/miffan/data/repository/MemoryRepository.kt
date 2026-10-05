package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.ayuilos.miffan.data.db.dao.MemoryDAO
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.model.AssistantMemory
import me.ayuilos.miffan.data.revision.MemorySnapshotItem
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.data.revision.RevisionRepository
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.utils.JsonInstant

/**
 * Memories grouped by owner (an assistant id or [GLOBAL_MEMORY_ID]). Every change of an owner's
 * memory set is recorded as a revision when [revisions] is available.
 */
class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val revisions: RevisionRepository? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"

        fun snapshot(memories: List<MemoryEntity>): String = JsonInstant.encodeToString(
            memories.sortedBy { it.id }.map {
                MemorySnapshotItem(it.id, it.content, it.createdAt, it.sourceConversationId, it.sourceMessageId)
            }
        )

        fun restore(snapshot: String): List<MemorySnapshotItem> = JsonInstant.decodeFromString(snapshot)
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    /** Full records including creation time and source, for the memory pages. */
    fun getMemoryRecordsFlow(ownerId: String): Flow<List<MemoryEntity>> = memoryDAO.getMemoriesOfAssistantFlow(ownerId)

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        recording(assistantId, summary = "") {
            memoryDAO.deleteMemoriesOfAssistant(assistantId)
        }
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        recording(old.assistantId, summary = content) {
            memoryDAO.updateMemory(newMemory)
        }
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val trigger = currentCoroutineContext()[RevisionOrigin]?.trigger
        val id = recording(assistantId, summary = content) {
            memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    sourceConversationId = trigger?.conversationId?.toString().orEmpty(),
                    sourceMessageId = trigger?.messageId?.toString().orEmpty(),
                )
            ).toInt()
        }
        return AssistantMemory(id = id, content = content)
    }

    suspend fun deleteMemory(id: Int) {
        val old = memoryDAO.getMemoryById(id) ?: return
        recording(old.assistantId, summary = old.content) {
            memoryDAO.deleteMemory(id)
        }
    }

    /** Replaces an owner's whole memory set, keeping the original ids (used by restore). */
    suspend fun replaceAll(ownerId: String, items: List<MemorySnapshotItem>) {
        recording(ownerId, summary = "") {
            memoryDAO.deleteMemoriesOfAssistant(ownerId)
            memoryDAO.insertAll(
                items.map {
                    MemoryEntity(it.id, ownerId, it.content, it.createdAt, it.sourceConversationId, it.sourceMessageId)
                }
            )
        }
    }

    private suspend fun <T> recording(ownerId: String, summary: String, change: suspend () -> T): T {
        val revisions = revisions ?: return change()
        val before = snapshot(memoryDAO.getMemoriesOfAssistant(ownerId))
        val result = change()
        revisions.record(
            subject = RevisionSubject.MEMORY,
            subjectId = ownerId,
            before = before,
            after = snapshot(memoryDAO.getMemoriesOfAssistant(ownerId)),
            defaultSummary = summary,
        )
        return result
    }
}
