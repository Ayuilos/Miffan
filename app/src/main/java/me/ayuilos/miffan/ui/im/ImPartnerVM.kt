package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.thread.ThreadRepository
import me.ayuilos.miffan.data.thread.ThreadService
import kotlin.uuid.Uuid

internal fun Assistant.memoryOwnerId() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

@OptIn(ExperimentalCoroutinesApi::class)
class ImPartnerVM(
    val assistantId: Uuid,
    private val settingsStore: SettingsStore,
    memoryRepository: MemoryRepository,
    private val conversations: ConversationRepository,
    private val threads: ThreadRepository,
    private val threadService: ThreadService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow.map { it.assistants.find { assistant -> assistant.id == assistantId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settingsFlow.value.assistants.find { it.id == assistantId })
    val memories = assistant.flatMapLatest { partner ->
        partner?.let { memoryRepository.getMemoryRecordsFlow(it.memoryOwnerId()) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun update(transform: (Assistant) -> Assistant) {
        settingsStore.update { settings -> settings.copy(assistants = settings.assistants.map {
            if (it.id == assistantId) transform(it) else it
        }) }
    }

    suspend fun changeTopic() = threadService.closeAll(threads.observeSegments(assistantId, 12).first())
    suspend fun deleteChats() = conversations.deleteConversationOfAssistant(assistantId)
}
