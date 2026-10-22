package me.ayuilos.miffan.ui.im.thread

import androidx.lifecycle.ViewModel
import me.ayuilos.miffan.data.repository.FavoriteRepository
import me.ayuilos.miffan.data.model.FavoriteType
import me.ayuilos.miffan.data.model.NodeFavoriteTarget
import me.ayuilos.miffan.data.favorite.NodeFavoriteAdapter
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
import kotlinx.coroutines.flow.scan
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
import me.ayuilos.miffan.data.thread.MergeResult
import me.ayuilos.miffan.data.thread.UndoableMerge
import me.ayuilos.miffan.data.thread.listPreviewText
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.db.dao.ThreadCatalogEntry
import me.ayuilos.miffan.data.thread.LiveSegmentHandoff
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.ThreadListState
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
data class ThreadTopic(
    val segmentId: Uuid,
    val title: String?,
    val topicIndex: Int,
    val preview: String = "",
    val speaker: MessageRole? = null,
    val updateAt: Instant = Instant.EPOCH,
)

private data class IndexedThreadSegments(
    val segments: List<Conversation>?,
    val indices: Map<Uuid, Int>,
    val earlier: Map<Uuid, Set<Uuid>>,
)

/**
 * Timeline items and whether the first page is already in them. They travel together so an empty
 * list is never read as an empty thread while the stored segments are still on their way in.
 */
data class ThreadTimelineState(val items: List<TimelineItem>, val loaded: Boolean)

/**
 * State and actions of one assistant's IM timeline. The UI renders [timeline] and never needs to
 * know about conversations: sending, replying and filtering are expressed in timeline terms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentThreadVM(
    val assistantId: Uuid,
    settingsStore: SettingsStore,
    private val threadRepository: ThreadRepository,
    noticeSource: ThreadNoticeSource,
    private val chatService: ChatService,
    private val threadService: ThreadService,
    private val revisionService: RevisionService,
    private val listState: ThreadListState,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    /** Messages the owner saved, by node, so the press menu offers to save or unsave. */
    val favoriteNodes: StateFlow<Set<Uuid>> = favoriteRepository.listByType(FavoriteType.NODE)
        .map { favorites -> favorites.mapNotNullTo(HashSet()) { NodeFavoriteAdapter.decodeRef(it)?.nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Saves the message to (or takes it out of) the owner's favorites, shown under Discover. */
    fun toggleFavorite(item: TimelineItem.Message, title: String) {
        viewModelScope.launch {
            if (favoriteRepository.isNodeFavorited(item.segmentId, item.node.id)) favoriteRepository.removeNodeFavorite(item.segmentId, item.node.id)
            else favoriteRepository.addNodeFavorite(NodeFavoriteTarget(item.segmentId, title, item.node.id, item.node))
        }
    }

    private val visible = MutableStateFlow(false)
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
                    conversations.filter { it.assistantId == assistantId && it.chatId == null }
                }
            }
        }

    // Shared, so every reader sees the same hand-off from a finished live segment to its stored copy.
    // Null until the first page of stored segments is in.
    private val loadedSegments: StateFlow<List<Conversation>?> = combine(storedSegments, liveSegments) { stored, live -> stored to live }
        .scan(LiveSegmentHandoff() to false) { (handoff, _), (stored, live) -> handoff.next(stored, live) to (stored != null) }
        .map { (handoff, loaded) -> handoff.merged.takeIf { loaded } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val segments: StateFlow<List<Conversation>> = loadedSegments.map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val generatingSegmentIds: StateFlow<Set<Uuid>> = liveSegments
        .map { live -> live.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val catalog: StateFlow<List<ThreadCatalogEntry>?> = threadRepository.observeCatalog(assistantId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val indexedSegments = combine(loadedSegments, catalog) { loaded, stored ->
        val dates = stored.orEmpty().associate { Uuid.parse(it.id) to Instant.ofEpochMilli(it.createAt) } +
            loaded.orEmpty().associate { it.id to it.createAt }
        val sorted = dates.entries.sortedWith(compareBy<Map.Entry<Uuid, Instant>> { it.value }.thenBy { it.key.toString() })
        IndexedThreadSegments(
            loaded.takeIf { stored != null },
            sorted.withIndex().associate { (index, entry) -> entry.key to index },
            loaded.orEmpty().associate { source ->
                source.id to dates.filter { (id, at) -> id != source.id && at < source.createAt }.keys
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IndexedThreadSegments(null, emptyMap(), emptyMap()))

    val newTopicPending: StateFlow<Boolean> = threadService.pendingNewTopic.map { assistantId in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, assistantId in threadService.pendingNewTopic.value)

    private var undoWindowVersion = 0L
    private val _undoableMerge = MutableStateFlow<UndoableMerge?>(null)
    val undoableMerge: StateFlow<UndoableMerge?> = _undoableMerge.asStateFlow()

    val timelineState: StateFlow<ThreadTimelineState> = combine(
        indexedSegments,
        noticeSource.observe(assistantId),
        generatingSegmentIds,
        _topicFilter,
        pending,
    ) { indexed, notices, generating, filter, pending ->
        val segments = indexed.segments
        val items = ThreadTimeline.build(segments.orEmpty(), notices, generating, filter,
            topicIndices = indexed.indices, earlierSegmentIds = indexed.earlier)
        val shown = items.mapNotNullTo(HashSet()) { (it as? TimelineItem.Message)?.message?.id }
        val outgoing = pending.filter { it.id !in shown }
        ThreadTimelineState(if (outgoing.isEmpty()) items else items.withPending(outgoing), loaded = segments != null)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThreadTimelineState(emptyList(), loaded = false))

    val timeline: StateFlow<List<TimelineItem>> = timelineState.map { it.items }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val topics: StateFlow<Map<Uuid, ThreadTopic>> = indexedSegments
        .map { indexed -> indexed.segments.orEmpty().associate { segment ->
            segment.id to segment.asTopic(indexed.indices[segment.id] ?: 0)
        } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Up to three earlier topics, including those above the loaded timeline page. */
    suspend fun mergeTargets(segmentId: Uuid): List<ThreadTopic> {
        val source = chatService.readThreadSegment(segmentId) ?: return emptyList()
        val stored = catalog.first { it != null }.orEmpty()
        val live = segments.value.associateBy { it.id }
        val dates = stored.associate { Uuid.parse(it.id) to Instant.ofEpochMilli(it.createAt) } + live.mapValues { it.value.createAt }
        val indices = dates.entries.sortedWith(compareBy<Map.Entry<Uuid, Instant>> { it.value }.thenBy { it.key.toString() })
            .withIndex().associate { (index, entry) -> entry.key to index }
        val updates = stored.associate { Uuid.parse(it.id) to Instant.ofEpochMilli(it.updateAt) } + live.mapValues { it.value.updateAt }
        return dates.filter { (id, at) -> id != segmentId && at < source.createAt }.keys
            .sortedByDescending { updates[it] }.take(3).mapNotNull { id ->
                (chatService.readThreadSegment(id) ?: threadRepository.getSegment(id))?.asTopic(indices.getValue(id))
            }
    }

    fun requestNewTopic() {
        _topicFilter.value = null
        _replyTarget.value = null
        threadService.requestNewTopic(assistantId)
    }
    fun cancelNewTopic() = threadService.cancelNewTopic(assistantId)

    /** Failures are surfaced with the thread's existing error cards. */
    fun merge(sourceId: Uuid, targetId: Uuid) {
        val version = undoWindowVersion
        viewModelScope.launch {
            try {
                when (val result = threadService.mergeSegment(assistantId, sourceId, targetId)) {
                    is MergeResult.Success -> {
                        _topicFilter.value = null
                        _replyTarget.value = null
                        if (version == undoWindowVersion) _undoableMerge.value = result.merge
                        else threadService.forgetMerge(assistantId)
                    }
                    is MergeResult.Failure -> chatService.addError(IllegalStateException(result.reason), targetId)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { chatService.addError(e, targetId) }
        }
    }

    fun undoMerge() {
        val merge = _undoableMerge.value ?: return
        viewModelScope.launch {
            try {
                when (val result = threadService.undoMerge(assistantId)) {
                    is MergeResult.Success -> _undoableMerge.value = null
                    is MergeResult.Failure -> {
                        _undoableMerge.value = null
                        chatService.addError(IllegalStateException(result.reason), merge.targetId)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { chatService.addError(e, merge.targetId) }
        }
    }

    private fun clearUndoMerge() {
        undoWindowVersion++
        _undoableMerge.value = null
        threadService.forgetMerge(assistantId)
    }

    init {
        // Everything shown while the thread is on screen counts as read.
        viewModelScope.launch {
            combine(visible, timeline) { shown, _ -> shown }.collect { shown ->
                if (shown) listState.markRead(assistantId)
            }
        }
        // A pending message is released once its segment shows it, so it never flickers.
        viewModelScope.launch {
            segments.collect { list ->
                val recorded = list.flatMapTo(HashSet()) { segment -> segment.messageNodes.map { it.message.id } }
                pending.update { outgoing -> outgoing.filter { it.id !in recorded } }
            }
        }
        // "换个话题" from the partner profile: the next message must not stick to an old topic.
        viewModelScope.launch {
            threadService.topicChanges.collect { changed ->
                if (changed == assistantId) {
                    _topicFilter.value = null
                    _replyTarget.value = null
                }
            }
        }
    }

    /** Errors of this thread's segments, oldest first. */
    val errors: StateFlow<List<ChatError>> = combine(chatService.errors, segments) { errors, segments ->
        val ids = segments.mapTo(HashSet()) { it.id }
        errors.filter { it.conversationId in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Sends [parts] (text, images, files) to the thread; routing picks the segment. */
    fun send(parts: List<UIMessagePart>, freshStart: Boolean = false, reviewedHistory: Boolean = false) {
        clearUndoMerge()
        val topicSegmentId = _topicFilter.value
        val replyTo = _replyTarget.value?.ref
        _replyTarget.value = null
        val message = UIMessage(role = MessageRole.USER, parts = parts)
        pending.update { it + message }
        viewModelScope.launch {
            try {
                threadService.send(
                    assistantId = assistantId,
                    content = parts,
                    segments = storedSegments.first { it != null }.orEmpty(),
                    replyTo = replyTo,
                    topicSegmentId = topicSegmentId,
                    freshStart = freshStart,
                    reviewedHistory = reviewedHistory,
                    messageId = message.id,
                    createdAt = message.createdAt,
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
        if (!item.canRegenerate) return
        viewModelScope.launch { threadService.regenerate(assistantId, item.segmentId, item.message) }
    }

    /**
     * Retries a failed reply in [segmentId] by answering its latest user message again. Unlike
     * [regenerate], this also works when the failure happened before any reply existed.
     */
    fun retry(segmentId: Uuid) {
        viewModelScope.launch {
            val segment = segments.value.firstOrNull { it.id == segmentId } ?: return@launch
            val message = segment.currentMessages.lastOrNull { it.role == MessageRole.USER } ?: return@launch
            threadService.regenerate(assistantId, segmentId, message)
        }
    }

    /** Approves or declines a tool call that waits for the user (for example turning on web search). */
    fun answerToolApproval(item: TimelineItem.Message, toolCallId: String, approved: Boolean, via: me.rerere.ai.ui.ToolDecisionVia = me.rerere.ai.ui.ToolDecisionVia.CARD) {
        viewModelScope.launch { threadService.answerTool(assistantId, item.segmentId, toolCallId, approved, via = via) }
    }

    /** The extra answers a card in [item] can give: always allow, and allow with corrected arguments. */
    internal fun toolCardActions(item: TimelineItem.Message) = ToolCardActions(
        alwaysAllow = { id -> viewModelScope.launch { threadService.alwaysAllowTool(assistantId, item.segmentId, id) } },
        grantStanding = { key -> threadService.grantStandingApproval(assistantId, key) },
        approveWithInput = { id, input -> viewModelScope.launch { threadService.approveToolWithInput(assistantId, item.segmentId, id, input) } },
    )

    /** Answers a tool call that asks the user a question. */
    fun answerToolQuestion(item: TimelineItem.Message, toolCallId: String, answer: String) {
        viewModelScope.launch { threadService.answerTool(assistantId, item.segmentId, toolCallId, approved = true, answer = answer) }
    }

    /** Stops every generating segment of this thread. */
    fun stop() {
        viewModelScope.launch {
            generatingSegmentIds.value.forEach { threadService.stop(it) }
        }
    }

    /** Called by the page when it starts or stops being visible. */
    fun setVisible(shown: Boolean) {
        visible.value = shown
        if (!shown) clearUndoMerge()
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

    override fun onCleared() {
        clearUndoMerge()
        super.onCleared()
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

private fun Conversation.asTopic(index: Int): ThreadTopic {
    val last = currentMessageNodes.lastOrNull {
        it.origin !is MessageOrigin.Partner && it.origin !is MessageOrigin.Event &&
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT)
    }
    return ThreadTopic(id, title.takeIf { it.isNotBlank() }, index,
        last?.message?.listPreviewText().orEmpty(), last?.role, updateAt)
}
