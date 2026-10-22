package me.ayuilos.miffan.ui.im

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.model.ChatOrganization
import me.ayuilos.miffan.data.model.OWNER_PARTICIPANT_ID
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.withInterfaceMode
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.thread.PreviewKind
import me.ayuilos.miffan.data.thread.listPreviewText
import me.ayuilos.miffan.data.thread.ThreadListPrefs
import me.ayuilos.miffan.data.thread.ThreadListState
import me.ayuilos.miffan.data.thread.ThreadListSummaries
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.utils.UpdateChecker
import me.ayuilos.miffan.utils.UpdateDownload
import me.ayuilos.miffan.utils.UpdateInfo
import me.ayuilos.miffan.utils.availableUpdate
import java.time.Instant
import kotlin.uuid.Uuid

/** One row of the Chats tab: a partner's thread, or a chat between partners. */
data class ImChatItem(
    /** The partner of a thread; for a partner chat, its first member. */
    val assistant: Assistant,
    val preview: String,
    val previewKind: PreviewKind,
    /** The preview is the user's own message. */
    val previewFromUser: Boolean,
    val lastActivity: Instant,
    val unread: Int,
    val pinned: Boolean,
    val typing: Boolean,
    /** The partner (or a member) holds a request the owner has not answered yet. */
    val waiting: Boolean = false,
    /** Set for a chat between partners: its id and every partner member. */
    val partnerChatId: Uuid? = null,
    val members: List<Assistant> = listOf(assistant),
    /** Who said the preview line in a partner chat. */
    val previewSender: Assistant? = null,
    /** A group chat's name; other rows are named after their partners. */
    val title: String? = null,
) {
    val chatId: Uuid get() = partnerChatId ?: assistant.id
    val isPartnerChat: Boolean get() = partnerChatId != null
}

/** The Chats tab laid out: pinned chats on top, then ungrouped chats, then each non-empty group. */
data class ImChatSections(
    val pinned: List<ImChatItem>,
    val ungrouped: List<ImChatItem>,
    val groups: List<ImChatGroupSection>,
    /** Every group, including empty ones, for the "move to group" picker. */
    val allGroups: List<ChatGroup>,
)

data class ImChatGroupSection(
    val group: ChatGroup,
    val chats: List<ImChatItem>,
    val collapsed: Boolean,
) {
    val unread: Int get() = chats.sumOf { it.unread }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImHomeVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val listState: ThreadListState,
    private val partnerChatService: PartnerChatService,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val availableUpdate: StateFlow<UpdateInfo?> = updateChecker.updateState
        .map { it.availableUpdate() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloadState = updateChecker.downloadState

    fun cancelUpdateDownload() = updateChecker.cancelUpdateDownload()

    fun installUpdate(context: Context): Boolean = updateChecker.installUpdate(context)

    fun downloadUpdate(context: Context, download: UpdateDownload) = updateChecker.downloadUpdate(context, download)

    /** Per partner chat, the members whose requests wait for the owner. */
    private val chatApprovals = partnerChatService.observeSummaries()
        .map { summaries -> summaries.map { it.chat.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id ->
                partnerChatService.observeApprovals(id).map { approvals -> id to approvals.mapTo(HashSet()) { it.memberId } }
            }) { it.toMap() }
        }

    /** Threads by latest activity; hidden threads stay out until new activity unless pinned. */
    private val threads = combine(
        settingsStore.settingsFlow,
        conversationRepository.observeLatestConversationOfEachAssistant(),
        chatService.getConversationJobs(),
        listState.value,
        chatApprovals,
    ) { settings, latest, jobs, prefs, approvals -> Snapshot(settings, latest, jobs.keys, prefs, approvals) }
        .mapLatest { (settings, latest, jobIds, prefs, approvals) ->
            val waitingElsewhere = approvals.values.flatMapTo(HashSet()) { it }
            val assistants = settings.assistants.associateBy { it.id }
            val typingAssistants = jobIds.mapNotNullTo(HashSet()) { chatService.getConversationFlow(it).value.assistantId }
            val organization = settings.chatOrganization
            val items = latest.mapNotNull { conversation ->
                val assistant = assistants[conversation.assistantId] ?: return@mapNotNull null
                val key = assistant.id.toString()
                val nodes = conversationRepository.getRecentMessageNodes(conversation.id, RECENT_MESSAGES)
                val summary = ThreadListSummaries.summarize(nodes, readAt = prefs.readAt[key] ?: 0L)
                val waiting = assistant.id in waitingElsewhere || nodes.any { node ->
                    node.message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }
                val lastActivity = maxOf(summary.lastActivity ?: conversation.updateAt, conversation.updateAt)
                val pinned = organization.isPinned(assistant.id)
                val hiddenAt = prefs.hiddenAt[key]
                if (!pinned && hiddenAt != null && lastActivity.toEpochMilli() <= hiddenAt) return@mapNotNull null
                ImChatItem(
                    assistant = assistant,
                    preview = summary.preview,
                    previewKind = summary.previewKind,
                    previewFromUser = summary.fromUser,
                    lastActivity = lastActivity,
                    unread = summary.unread,
                    pinned = pinned,
                    typing = assistant.id in typingAssistants,
                    waiting = waiting,
                )
            }
            // A pinned partner stays on top even before its first message.
            val started = items.mapTo(HashSet()) { it.chatId }
            val unstarted = organization.pinned.filter { it !in started }.mapNotNull { id ->
                val assistant = assistants[id] ?: return@mapNotNull null
                ImChatItem(assistant, preview = "", previewKind = PreviewKind.EMPTY, previewFromUser = false,
                    lastActivity = Instant.EPOCH, unread = 0, pinned = true, typing = assistant.id in typingAssistants,
                    waiting = assistant.id in waitingElsewhere)
            }
            items + unstarted
        }
        // Read by both the Chats list and the Partners tab's lights; summarizing threads is not cheap.
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Each partner's own thread by partner, for the Partners tab's working and waiting lights. */
    val partnerThreads: StateFlow<Map<Uuid, ImChatItem>> = threads.map { items -> items.associateBy { it.assistant.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Chats between partners; only groups count unread, a chat of two partners never does. */
    private val partnerChats = combine(
        partnerChatService.observeSummaries(), settingsStore.settingsFlow, listState.value, chatApprovals,
    ) { summaries, settings, prefs, approvals ->
        val assistants = settings.assistants.associateBy { it.id }
        summaries.mapNotNull { summary ->
            val members = summary.chat.memberIds.mapNotNull { assistants[it] }
            if (members.size < 2) return@mapNotNull null
            val pinned = settings.chatOrganization.isPinned(summary.chat.id)
            val hiddenAt = prefs.hiddenAt[summary.chat.id.toString()]
            if (!pinned && hiddenAt != null && summary.lastActivity.toEpochMilli() <= hiddenAt) return@mapNotNull null
            val last = summary.last
            ImChatItem(
                assistant = members.first(),
                preview = last?.text?.let(::listPreviewText).orEmpty(),
                previewKind = if (last == null) PreviewKind.EMPTY else PreviewKind.TEXT,
                previewFromUser = last?.senderId == OWNER_PARTICIPANT_ID,
                lastActivity = summary.lastActivity,
                unread = summary.unread,
                pinned = pinned,
                typing = summary.typing.isNotEmpty(),
                waiting = approvals[summary.chat.id].orEmpty().isNotEmpty(),
                title = summary.chat.title.takeIf { summary.chat.kind == PartnerChatKind.GROUP && it.isNotBlank() },
                partnerChatId = summary.chat.id,
                members = members,
                previewSender = last?.senderId?.let { assistants[it] },
            )
        }
    }

    private val chats = combine(threads, partnerChats) { threads, partnerChats ->
        (threads + partnerChats).sortedByDescending { it.lastActivity }
    }

    val sections: StateFlow<ImChatSections?> = combine(chats, settingsStore.settingsFlow, listState.value) { chats, settings, prefs ->
        val organization = settings.chatOrganization
        val byId = chats.associateBy { it.chatId }
        val pinned = organization.pinned.mapNotNull { byId[it] }
        val rest = chats.filterNot { it.pinned }
        val grouped = organization.groups.flatMapTo(HashSet()) { it.chatIds }
        ImChatSections(
            pinned = pinned,
            ungrouped = rest.filter { it.chatId !in grouped },
            groups = organization.groups.mapNotNull { group ->
                val members = group.chatIds.toSet()
                rest.filter { it.chatId in members }.takeIf { it.isNotEmpty() }?.let {
                    ImChatGroupSection(group, it, collapsed = group.id.toString() in prefs.collapsedGroups)
                }
            },
            allGroups = organization.groups,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun organize(transform: (ChatOrganization) -> ChatOrganization) = updateSettings {
        it.copy(chatOrganization = transform(it.chatOrganization))
    }

    fun setPinned(chatId: Uuid, pinned: Boolean) = organize { if (pinned) it.pin(chatId) else it.unpin(chatId) }

    fun moveToGroup(chatId: Uuid, groupId: Uuid?) = organize { it.moveToGroup(chatId, groupId) }

    fun createGroup(name: String, chatId: Uuid?) = organize { it.createGroup(name, chatId) }

    fun renameGroup(groupId: Uuid, name: String) = organize { it.renameGroup(groupId, name) }

    fun moveGroup(groupId: Uuid, offset: Int) = organize { it.moveGroup(groupId, offset) }

    fun deleteGroup(groupId: Uuid) = organize { it.deleteGroup(groupId) }

    fun setGroupCollapsed(groupId: Uuid, collapsed: Boolean) = listState.setGroupCollapsed(groupId, collapsed)

    /** "不显示": hides the row until the thread has new activity; history is kept. */
    fun hide(chatId: Uuid) = listState.hide(chatId)

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    suspend fun addPartner(assistant: Assistant) {
        settingsStore.update { settings ->
            if (settings.assistants.any { it.id == assistant.id }) settings
            else settings.copy(assistants = settings.assistants + assistant)
        }
    }

    suspend fun switchInterfaceMode(mode: InterfaceMode) {
        settingsStore.update { it.withInterfaceMode(mode) }
    }

    private data class Snapshot(
        val settings: Settings,
        val latest: List<Conversation>,
        val jobIds: Set<Uuid>,
        val prefs: ThreadListPrefs,
        val approvals: Map<Uuid, Set<Uuid>>,
    )

    private companion object {
        const val RECENT_MESSAGES = 40
    }
}
