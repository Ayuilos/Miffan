package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.withInterfaceMode
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import java.time.Instant
import kotlin.uuid.Uuid

/** One row of the Chats tab: an assistant and its most recently active conversation. */
data class ImChatItem(
    val assistant: Assistant,
    val conversationId: Uuid,
    val preview: String,
    val updateAt: Instant,
    val typing: Boolean,
)

class ImHomeVM(
    private val settingsStore: SettingsStore,
    conversationRepository: ConversationRepository,
    chatService: ChatService,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val chats: StateFlow<List<ImChatItem>?> = combine(
        settingsStore.settingsFlow,
        conversationRepository.observeLatestConversationOfEachAssistant(),
        chatService.getConversationJobs(),
    ) { settings, latest, jobs ->
        val assistants = settings.assistants.associateBy { it.id }
        latest.mapNotNull { conversation ->
            val assistant = assistants[conversation.assistantId] ?: return@mapNotNull null
            ImChatItem(
                assistant = assistant,
                conversationId = conversation.id,
                preview = conversation.title,
                updateAt = conversation.updateAt,
                typing = conversation.id in jobs,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    suspend fun switchInterfaceMode(mode: InterfaceMode) {
        settingsStore.update { it.withInterfaceMode(mode) }
    }
}
