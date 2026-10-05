package me.ayuilos.miffan.ui.im

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.withInterfaceMode
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.thread.PreviewKind
import me.ayuilos.miffan.data.thread.ThreadListPrefs
import me.ayuilos.miffan.data.thread.ThreadListState
import me.ayuilos.miffan.data.thread.ThreadListSummaries
import me.ayuilos.miffan.service.ChatService
import java.time.Instant
import kotlin.uuid.Uuid

/** One row of the Chats tab: an assistant's thread. */
data class ImChatItem(
    val assistant: Assistant,
    val preview: String,
    val previewKind: PreviewKind,
    /** The preview is the user's own message. */
    val previewFromUser: Boolean,
    val lastActivity: Instant,
    val unread: Int,
    val pinned: Boolean,
    val typing: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ImHomeVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val listState: ThreadListState,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    /** Pinned threads first, then by latest activity; hidden threads stay out until new activity. */
    val chats: StateFlow<List<ImChatItem>?> = combine(
        settingsStore.settingsFlow,
        conversationRepository.observeLatestConversationOfEachAssistant(),
        chatService.getConversationJobs(),
        listState.value,
    ) { settings, latest, jobs, prefs -> Snapshot(settings, latest, jobs.keys, prefs) }
        .mapLatest { (settings, latest, jobIds, prefs) ->
            val assistants = settings.assistants.associateBy { it.id }
            val typingAssistants = jobIds.mapNotNullTo(HashSet()) { chatService.getConversationFlow(it).value.assistantId }
            latest.mapNotNull { conversation ->
                val assistant = assistants[conversation.assistantId] ?: return@mapNotNull null
                val key = assistant.id.toString()
                val summary = ThreadListSummaries.summarize(
                    conversationRepository.getRecentMessages(conversation.id, RECENT_MESSAGES),
                    readAt = prefs.readAt[key] ?: 0L,
                )
                val lastActivity = maxOf(summary.lastActivity ?: conversation.updateAt, conversation.updateAt)
                val hiddenAt = prefs.hiddenAt[key]
                if (hiddenAt != null && lastActivity.toEpochMilli() <= hiddenAt) return@mapNotNull null
                ImChatItem(
                    assistant = assistant,
                    preview = summary.preview,
                    previewKind = summary.previewKind,
                    previewFromUser = summary.fromUser,
                    lastActivity = lastActivity,
                    unread = summary.unread,
                    pinned = key in prefs.pinned,
                    typing = assistant.id in typingAssistants,
                )
            }.sortedWith(compareByDescending<ImChatItem> { it.pinned }.thenByDescending { it.lastActivity })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPinned(assistant: Assistant, pinned: Boolean) = listState.setPinned(assistant.id, pinned)

    /** "不显示": hides the row until the thread has new activity; history is kept. */
    fun hide(assistant: Assistant) = listState.hide(assistant.id)

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    suspend fun switchInterfaceMode(mode: InterfaceMode) {
        settingsStore.update { it.withInterfaceMode(mode) }
    }

    private data class Snapshot(
        val settings: Settings,
        val latest: List<Conversation>,
        val jobIds: Set<Uuid>,
        val prefs: ThreadListPrefs,
    )

    private companion object {
        const val RECENT_MESSAGES = 40
    }
}
