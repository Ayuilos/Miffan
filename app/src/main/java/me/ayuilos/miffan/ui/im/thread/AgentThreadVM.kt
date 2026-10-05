package me.ayuilos.miffan.ui.im.thread

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.revision.RestoreResult
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.ThreadNoticeSource
import me.ayuilos.miffan.data.thread.ThreadRepository
import me.ayuilos.miffan.data.thread.ThreadService
import me.ayuilos.miffan.data.thread.ThreadTimeline
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.service.ChatError
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import kotlin.uuid.Uuid

/** A topic (segment) the user can filter the timeline to. */
data class ThreadTopic(val segmentId: Uuid, val title: String, val topicIndex: Int)

/**
 * State and actions of one assistant's IM timeline. The UI renders [timeline] and never needs to
 * know about conversations: sending, replying and filtering are expressed in timeline terms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentThreadVM(
    val assistantId: Uuid,
    settingsStore: SettingsStore,
    threadRepository: ThreadRepository,
    noticeSource: ThreadNoticeSource,
    private val chatService: ChatService,
    private val threadService: ThreadService,
    private val revisionService: RevisionService,
) : ViewModel() {
    private val segmentLimit = MutableStateFlow(PAGE_SEGMENTS)

    private val _topicFilter = MutableStateFlow<Uuid?>(null)

    /** Segment shown alone, or null for the whole thread. */
    val topicFilter: StateFlow<Uuid?> = _topicFilter.asStateFlow()

    /** Sent messages still being routed; shown immediately until the segment records them. */
    private val pending = MutableStateFlow<List<UIMessage>>(emptyList())

    private val _replyTarget = MutableStateFlow<TimelineItem.Message?>(null)

    /** Message the next send replies to; shown above the composer. */
    val replyTarget: StateFlow<TimelineItem.Message?> = _replyTarget.asStateFlow()

    val assistant: StateFlow<Assistant?> = settingsStore.settingsFlow
        .map { it.getAssistantById(assistantId) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settingsFlow.value.getAssistantById(assistantId))

    private val storedSegments: StateFlow<List<Conversation>?> = segmentLimit
        .flatMapLatest { limit -> threadRepository.observeSegments(assistantId, limit) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Segments with an active generation, read from their live sessions so streaming shows. */
    private val liveSegments: Flow<List<Conversation>> = chatService.getConversationJobs()
        .map { jobs -> jobs.keys }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(ids.map { chatService.getConversationFlow(it) }) { conversations ->
                    conversations.filter { it.assistantId == assistantId }
                }
            }
        }

    private val segments: Flow<List<Conversation>> = combine(storedSegments, liveSegments) { stored, live ->
        val liveById = live.associateBy { it.id }
        val merged = stored.orEmpty().map { liveById[it.id] ?: it }
        merged + live.filter { segment -> merged.none { it.id == segment.id } }
    }

    /** True once the first page has loaded; distinguishes an empty thread from loading. */
    val loaded: StateFlow<Boolean> = storedSegments.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val generatingSegmentIds: StateFlow<Set<Uuid>> = liveSegments
        .map { live -> live.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val timeline: StateFlow<List<TimelineItem>> = combine(
        segments,
        noticeSource.observe(assistantId),
        generatingSegmentIds,
        _topicFilter,
        pending,
    ) { segments, notices, generating, filter, pending ->
        val items = ThreadTimeline.build(segments, notices, generating, filter)
        val shown = items.mapNotNullTo(HashSet()) { (it as? TimelineItem.Message)?.message?.id }
        val outgoing = pending.filter { it.id !in shown }
        if (outgoing.isEmpty()) items else items.withPending(outgoing)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val topics: StateFlow<Map<Uuid, ThreadTopic>> = segments
        .map { list ->
            list.sortedBy { it.createAt }.withIndex().associate { (index, segment) ->
                segment.id to ThreadTopic(segment.id, segment.title, index)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // A pending message is released once its segment shows it, so it never flickers.
        viewModelScope.launch {
            segments.collect { list ->
                val recorded = list.flatMapTo(HashSet()) { segment -> segment.messageNodes.map { it.message.id } }
                pending.update { outgoing -> outgoing.filter { it.id !in recorded } }
            }
        }
    }

    /** Errors of this thread's segments, oldest first. */
    val errors: StateFlow<List<ChatError>> = combine(chatService.errors, segments) { errors, segments ->
        val ids = segments.mapTo(HashSet()) { it.id }
        errors.filter { it.conversationId in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Sends [parts] (text, images, files) to the thread; routing picks the segment. */
    fun send(parts: List<UIMessagePart>) {
        val replyTo = _replyTarget.value?.ref
        _replyTarget.value = null
        val message = UIMessage(role = MessageRole.USER, parts = parts)
        pending.update { it + message }
        viewModelScope.launch {
            try {
                threadService.send(
                    assistantId = assistantId,
                    content = parts,
                    segments = storedSegments.value.orEmpty(),
                    replyTo = replyTo,
                    topicSegmentId = _topicFilter.value,
                    messageId = message.id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending.update { list -> list.filter { it.id != message.id } }
                chatService.addError(e)
            }
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        send(listOf(UIMessagePart.Text(text)))
    }

    fun setReplyTarget(item: TimelineItem.Message?) {
        _replyTarget.value = item
    }

    fun setTopicFilter(segmentId: Uuid?) {
        _topicFilter.value = segmentId
    }

    fun regenerate(item: TimelineItem.Message) {
        if (item.canRegenerate) threadService.regenerate(item.segmentId, item.message)
    }

    /**
     * Retries a failed reply in [segmentId] by answering its latest user message again. Unlike
     * [regenerate], this also works when the failure happened before any reply existed.
     */
    fun retry(segmentId: Uuid) {
        viewModelScope.launch {
            val segment = segments.first().firstOrNull { it.id == segmentId } ?: return@launch
            val message = segment.currentMessages.lastOrNull { it.role == MessageRole.USER } ?: return@launch
            threadService.regenerate(segmentId, message)
        }
    }

    /** Stops every generating segment of this thread. */
    fun stop() {
        viewModelScope.launch {
            generatingSegmentIds.value.forEach { threadService.stop(it) }
        }
    }

    /** "换个话题": the next message starts a fresh segment. */
    fun startNewTopic() {
        viewModelScope.launch { threadService.closeAll(storedSegments.value.orEmpty()) }
    }

    fun loadMore() {
        segmentLimit.value += PAGE_SEGMENTS
    }

    fun dismissError(error: ChatError) = chatService.dismissError(error.id)

    /** Reverts the change behind an undoable notice; [onResult] reports a conflict or success. */
    fun undoNotice(notice: ThreadNotice, onResult: (RestoreResult) -> Unit = {}) {
        val revisionId = notice.revisionId ?: return
        viewModelScope.launch { onResult(revisionService.undo(revisionId)) }
    }

    companion object {
        const val PAGE_SEGMENTS = 12
    }
}

/** Appends not-yet-routed messages after the timeline, followed by a typing row. */
private fun List<TimelineItem>.withPending(outgoing: List<UIMessage>): List<TimelineItem> {
    val base = filterNot { it is TimelineItem.Typing }
    val typing = filterIsInstance<TimelineItem.Typing>().firstOrNull()
    val last = base.lastOrNull { it is TimelineItem.Message } as? TimelineItem.Message
    val pendingItems = outgoing.mapIndexed { index, message ->
        TimelineItem.Message(
            segmentId = PENDING_SEGMENT,
            node = MessageNode.of(message),
            at = Instant.now(),
            topicIndex = last?.topicIndex ?: 0,
            quote = null,
            groupedWithPrevious = index > 0 || last?.message?.role == MessageRole.USER,
            streaming = false,
            canRegenerate = false,
        )
    }
    return base + pendingItems + TimelineItem.Typing(typing?.segmentIds.orEmpty())
}

/** Segment id of messages that are still being routed. */
val PENDING_SEGMENT: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
