package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.revision.RevisionService
import kotlin.uuid.Uuid

internal data class ImMemoryRow(val memory: MemoryEntity, val partner: Assistant?, val sourceAssistantId: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class ImMemoryVM(
    val ownerId: String,
    private val memoriesRepository: MemoryRepository,
    private val settingsStore: SettingsStore,
    private val conversations: ConversationRepository,
    private val revisionService: RevisionService,
) : ViewModel() {
    internal val rows = combine(memoriesRepository.getMemoryRecordsFlow(ownerId), settingsStore.settingsFlow) { memories, settings -> memories to settings }
        .mapLatest { (memories, settings) ->
            val owners = memories.map { it.sourceConversationId }.filter { it.isNotBlank() }.distinct().associateWith { source ->
                runCatching { Uuid.parse(source) }.getOrNull()?.let { conversations.getAssistantIdOf(it) }
            }
            memories.sortedByDescending { it.createdAt }.map { memory ->
                val sourceId = owners[memory.sourceConversationId]
                ImMemoryRow(memory, settings.assistants.find { it.id == sourceId || (sourceId == null && it.id.toString() == ownerId) }, sourceId?.toString())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Deletes [memory] and returns the revision to undo it with. */
    suspend fun delete(memory: MemoryEntity): String? = memoriesRepository.deleteMemory(memory.id)?.id

    suspend fun undo(id: String) = revisionService.undo(id)
}
