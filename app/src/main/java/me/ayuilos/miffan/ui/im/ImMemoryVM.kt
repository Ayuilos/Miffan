package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.*
import kotlin.uuid.Uuid

internal data class ImMemoryRow(val memory: MemoryEntity, val partner: Assistant?, val sourceAssistantId: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class ImMemoryVM(
    val ownerId: String,
    private val memoriesRepository: MemoryRepository,
    private val settingsStore: SettingsStore,
    private val conversations: ConversationRepository,
    private val revisions: RevisionRepository,
    private val revisionService: RevisionService,
) : ViewModel() {
    internal val rows = combine(memoriesRepository.getMemoryRecordsFlow(ownerId), settingsStore.settingsFlow) { memories, settings -> memories to settings }
        .mapLatest { (memories, settings) ->
            val owners = memories.map { it.sourceConversationId }.filter { it.isNotBlank() }.distinct().associateWith { source ->
                runCatching { Uuid.parse(source) }.getOrNull()?.let { conversations.getConversationById(it)?.assistantId }
            }
            memories.sortedByDescending { it.createdAt }.map { memory ->
                val sourceId = owners[memory.sourceConversationId]
                ImMemoryRow(memory, settings.assistants.find { it.id == sourceId || (sourceId == null && it.id.toString() == ownerId) }, sourceId?.toString())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Capture this deletion's revision, even if another memory edit races with the UI. */
    suspend fun delete(memory: MemoryEntity): String? {
        val operation = "im-memory-delete-${Uuid.random()}"
        withContext(RevisionOrigin(RevisionAuthor.USER, toolCallId = operation)) { memoriesRepository.deleteMemory(memory.id) }
        return revisions.history(RevisionSubject.MEMORY, ownerId).first().find { it.toolCallId == operation }?.id
    }

    suspend fun undo(id: String) = revisionService.undo(id)
}
