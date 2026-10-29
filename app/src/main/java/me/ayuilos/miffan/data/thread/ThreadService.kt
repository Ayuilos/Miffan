package me.ayuilos.miffan.data.thread

import me.rerere.ai.core.ReasoningLevel
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.ayuilos.miffan.data.model.TopicStart
import me.ayuilos.miffan.data.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.service.hasPendingToolApprovals
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid

private const val TAG = "ThreadService"

/** How long an unsaved topic stays a routing candidate. */
private const val RECENT_TOPIC_MILLIS = 10 * 60 * 1000L

/** Sends messages into an assistant's IM thread, choosing the segment for each message. */
class ThreadService(
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val router: SegmentRouter,
    private val summarizer: SegmentSummarizer,
    private val conversationRepository: ConversationRepository,
) {
    /** Serializes routing so each decision sees the topics that earlier messages just opened. */
    private val routeMutex = Mutex()

    /** Topics opened in this process, by segment id, until the stored segments include them. */
    private val recentTopics = ConcurrentHashMap<Uuid, RecentTopic>()

    private data class RecentTopic(val assistantId: Uuid, val label: String, val at: Instant)

    private val _topicChanges = MutableSharedFlow<Uuid>(extraBufferCapacity = 8)

    /** Assistants whose user explicitly changed the topic; open threads drop their topic focus. */
    val topicChanges: SharedFlow<Uuid> = _topicChanges.asSharedFlow()

    private val _pendingNewTopic = MutableStateFlow<Set<Uuid>>(emptySet())
    val pendingNewTopic: StateFlow<Set<Uuid>> = _pendingNewTopic.asStateFlow()

    fun requestNewTopic(assistantId: Uuid) {
        _pendingNewTopic.update { it + assistantId }
        _topicChanges.tryEmit(assistantId)
    }

    fun cancelNewTopic(assistantId: Uuid) {
        _pendingNewTopic.update { it - assistantId }
    }

    /**
     * Sends [content] and returns the segment that received it. An explicit reply or an active
     * topic filter decides the segment without routing.
     */
    suspend fun send(
        assistantId: Uuid,
        content: List<UIMessagePart>,
        segments: List<Conversation>,
        replyTo: MessageRef? = null,
        topicSegmentId: Uuid? = null,
        messageId: Uuid = Uuid.random(),
        createdAt: LocalDateTime? = null,
        freshStart: Boolean = false,
        reviewedHistory: Boolean = false,
        /** Overrides the partner's reasoning level for this message's reply. */
        reasoningLevel: ReasoningLevel? = null,
    ): Uuid {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?: error("Assistant not found: $assistantId")
        return routeMutex.withLock {
            // Include off-page segments and refresh live sessions before deciding or closing.
            val stored = conversationRepository.getConversationsOfAssistant(assistantId).first()
            val storedIds = stored.mapTo(HashSet()) { it.id }
            val loaded = segments.associateBy { it.id }
            val all = (stored + segments).distinctBy { it.id }.filter { it.chatId == null }
                .mapNotNull { segment ->
                    if (segment.threadClosedAt == 0L || segment.id !in storedIds) {
                        chatService.readThreadSegment(segment.id) ?: segment.takeIf { it.id in storedIds }
                    } else loaded[segment.id] ?: segment
                }
            val now = Instant.now()
            val known = all.mapTo(HashSet()) { it.id }
            recentTopics.entries.removeAll { (id, topic) -> id in known || now.toEpochMilli() - topic.at.toEpochMilli() > RECENT_TOPIC_MILLIS }
            val unsaved = recentTopics.filter { it.value.assistantId == assistantId }.map { (id, topic) ->
                chatService.readThreadSegment(id) ?: Conversation(id = id, assistantId = assistantId,
                    title = topic.label, messageNodes = emptyList(), createAt = topic.at, updateAt = topic.at)
            }
            val candidates = (all + unsaved).sortedByDescending { it.updateAt }
            val forced = topicSegmentId ?: replyTo?.conversationId
                ?: candidates.filter { it.hasPendingToolApprovals() }.maxByOrNull { it.updateAt }?.id
            if (forced != null) {
                val target = candidates.firstOrNull { it.id == forced } ?: chatService.readThreadSegment(forced)
                require(target != null && target.assistantId == assistantId && target.chatId == null) {
                    "Forced topic does not belong to this thread"
                }
            }
            val ownerStart = assistantId in _pendingNewTopic.value
            val decision = when {
                forced != null -> RouteDecision.Existing(forced, "forced target")
                ownerStart || freshStart -> RouteDecision.New(
                    "explicit fresh topic",
                    closeSegmentIds = candidates.filter { it.threadClosedAt == 0L }.mapTo(HashSet()) { it.id },
                    start = if (ownerStart) TopicStart.OWNER else TopicStart.WELCOME,
                )
                else -> router.route(RouteRequest(assistant, content, candidates, now, reviewedHistory = reviewedHistory))
            }
            Log.i(TAG, "route ${assistant.id}: $decision")
            val segmentId = when (decision) {
                is RouteDecision.Existing -> decision.segmentId
                is RouteDecision.New -> {
                    closeSegments(candidates, decision.closeSegmentIds)
                    decision.closeSegmentIds.forEach { recentTopics.remove(it) }
                    Uuid.random()
                }
            }
            val start = (decision as? RouteDecision.New)?.start?.takeIf { candidates.isNotEmpty() }
            if (segmentId !in known) {
                val label = recentTopics[segmentId]?.label
                    ?: content.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.take(120)
                recentTopics[segmentId] = RecentTopic(assistantId, label, now)
            }
            deliver(assistantId, segmentId, content, replyTo, messageId, createdAt, start, reasoningLevel)
            if (decision is RouteDecision.New && ownerStart) cancelNewTopic(assistantId)
            undoMerges.remove(assistantId)
            segmentId
        }
    }

    private suspend fun deliver(assistantId: Uuid, segmentId: Uuid, content: List<UIMessagePart>, replyTo: MessageRef?, messageId: Uuid, createdAt: LocalDateTime?, start: TopicStart?, reasoningLevel: ReasoningLevel?) {
        chatService.openThreadSegment(segmentId, assistantId, start)
        chatService.sendMessage(segmentId, content, immediately = false, replyTo = replyTo, messageId = messageId, createdAt = createdAt,
            reasoningLevel = reasoningLevel)
        chatService.resumeMessageQueue(segmentId)
    }

    /**
     * Delivers a message the owner did not write and lets the partner answer it, without routing:
     * into [segmentId] when given (e.g. the topic that asked another partner), otherwise the newest
     * open segment, otherwise a new one. Returns the segment. The answer arrives like any reply:
     * unread in the Chats list and a notification while the app is in the background. The turn
     * has no owner trigger, so it cannot change settings, grant permissions or edit memories.
     */
    suspend fun deliverFrom(
        origin: MessageOrigin,
        assistantId: Uuid,
        content: List<UIMessagePart>,
        segmentId: Uuid? = null,
    ): Uuid = routeMutex.withLock {
        requireNotNull(settingsStore.settingsFlow.first { !it.init }.getAssistantById(assistantId)) {
            "Assistant not found: $assistantId"
        }
        val stored = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val known = stored.mapTo(HashSet()) { it.id }
        recentTopics.entries.removeAll { it.key in known }
        val candidates = stored.filter { it.chatId == null } + recentTopics.filter { it.value.assistantId == assistantId }.map { (id, topic) ->
            Conversation(id = id, assistantId = assistantId, messageNodes = emptyList(), updateAt = topic.at)
        }
        require(segmentId == null || !conversationRepository.isPartnerPerspective(segmentId)) {
            "Cannot deliver an IM event to a partner perspective"
        }
        val selected = segmentId ?: candidates.filter { it.threadClosedAt == 0L }
            .maxByOrNull { it.updateAt }?.id ?: Uuid.random()
        val existingAssistant = conversationRepository.getAssistantIdOf(selected)
        require(existingAssistant == null || existingAssistant == assistantId) { "Segment belongs to another assistant" }
        if (selected !in known) {
            recentTopics[selected] = RecentTopic(assistantId, content.filterIsInstance<UIMessagePart.Text>()
                .joinToString(" ") { it.text }.take(120), Instant.now())
        }
        val start = TopicStart.AUTO.takeIf { candidates.isNotEmpty() && candidates.none { it.id == selected } }
        chatService.openThreadSegment(selected, assistantId, start)
        chatService.sendMessage(selected, content, immediately = false, origin = origin)
        chatService.resumeMessageQueue(selected)
        selected
    }

    /** Put a partner's note in its owner-facing thread without starting a model turn. */
    internal suspend fun deliverNote(assistantId: Uuid, origin: MessageOrigin.Event, text: String) = routeMutex.withLock {
        requireNotNull(settingsStore.settingsFlow.first { !it.init }.getAssistantById(assistantId))
        val stored = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val known = stored.mapTo(HashSet()) { it.id }
        recentTopics.entries.removeAll { it.key in known }
        val candidates = stored.filter { it.chatId == null && it.threadClosedAt == 0L } +
            recentTopics.filter { it.value.assistantId == assistantId }.map { (id, topic) ->
                Conversation(id = id, assistantId = assistantId, messageNodes = emptyList(), updateAt = topic.at)
            }
        val selected = candidates.maxByOrNull { it.updateAt }?.id ?: Uuid.random()
        if (candidates.isEmpty() && stored.any { it.chatId == null }) {
            chatService.openThreadSegment(selected, assistantId, TopicStart.AUTO)
        }
        chatService.appendPartnerNote(selected, assistantId, origin, text)
    }

    /** An event line in the owner-facing thread with no model turn, in the newest open segment. */
    internal suspend fun deliverRecord(assistantId: Uuid, origin: MessageOrigin.Event, text: String) = routeMutex.withLock {
        requireNotNull(settingsStore.settingsFlow.first { !it.init }.getAssistantById(assistantId))
        val stored = conversationRepository.getConversationsOfAssistant(assistantId).first()
        val known = stored.mapTo(HashSet()) { it.id }
        recentTopics.entries.removeAll { it.key in known }
        val candidates = stored.filter { it.chatId == null && it.threadClosedAt == 0L } +
            recentTopics.filter { it.value.assistantId == assistantId }.map { (id, topic) ->
                Conversation(id = id, assistantId = assistantId, messageNodes = emptyList(), updateAt = topic.at)
            }
        val selected = candidates.maxByOrNull { it.updateAt }?.id ?: Uuid.random()
        if (candidates.isEmpty() && stored.any { it.chatId == null }) {
            chatService.openThreadSegment(selected, assistantId, TopicStart.AUTO)
        }
        chatService.appendEventRecord(selected, assistantId, origin, text)
    }

    /** Closes the supplied open segments and schedules their summaries. */
    suspend fun closeAll(assistantId: Uuid, segments: List<Conversation>) {
        closeSegments(segments, segments.filter { it.threadClosedAt == 0L }.mapTo(HashSet()) { it.id })
        _topicChanges.emit(assistantId)
    }

    private data class MergeSnapshot(val source: Conversation, val target: Conversation, val owners: Set<Uuid>, val handle: UndoableMerge)
    private val undoMerges = ConcurrentHashMap<Uuid, MergeSnapshot>()

    fun forgetMerge(assistantId: Uuid) { undoMerges.remove(assistantId) }

    suspend fun mergeSegment(assistantId: Uuid, sourceId: Uuid, targetId: Uuid): MergeResult = routeMutex.withLock {
        if (sourceId == targetId) return@withLock MergeResult.Failure("Choose a different topic")
        if (chatService.isThreadSegmentBusy(sourceId) || chatService.isThreadSegmentBusy(targetId)) {
            return@withLock MergeResult.Failure("A topic is still generating or has queued messages")
        }
        val source = chatService.readThreadSegment(sourceId)
            ?: return@withLock MergeResult.Failure("Source topic no longer exists")
        val target = chatService.readThreadSegment(targetId)
            ?: return@withLock MergeResult.Failure("Target topic no longer exists")
        if (listOf(source, target).any { it.assistantId != assistantId || it.chatId != null } || target.createAt >= source.createAt) {
            return@withLock MergeResult.Failure("Choose an earlier topic of this partner")
        }
        val plan = planSegmentMerge(source, target)
        val handle = UndoableMerge(assistantId, sourceId, targetId, target.title.takeIf { it.isNotBlank() },
            source.currentMessageNodes.firstOrNull()?.let { MessageRef(targetId, it.message.id) })
        chatService.applyThreadMerge(source, plan.merged)
        recentTopics.remove(sourceId)
        undoMerges[assistantId] = MergeSnapshot(source, target, plan.merged.ownerMessageIds(), handle)
        if (plan.regenerate != null) {
            chatService.openThreadSegment(targetId, assistantId)
            chatService.regenerateAtMessage(targetId, plan.regenerate.message, continuationNodeIds = plan.continuationIds)
        }
        MergeResult.Success(handle)
    }

    suspend fun undoMerge(assistantId: Uuid): MergeResult = routeMutex.withLock {
        val snapshot = undoMerges[assistantId] ?: return@withLock MergeResult.Failure("No merge to undo")
        fun Conversation.hasNewOwner() = (ownerMessageIds() - snapshot.owners).isNotEmpty()
        if (chatService.readThreadSegment(snapshot.source.id) != null) {
            return@withLock MergeResult.Failure("Source topic has already been restored")
        }
        val target = chatService.readThreadSegment(snapshot.target.id)
            ?: return@withLock MergeResult.Failure("Target topic no longer exists")
        if (target.hasNewOwner() || chatService.hasQueuedOwnerMessage(target.id)) {
            undoMerges.remove(assistantId)
            return@withLock MergeResult.Failure("A new owner message prevents undo")
        }
        chatService.stopGeneration(target.id)
        val stopped = chatService.readThreadSegment(target.id)
        if (stopped == null || stopped.hasNewOwner() || chatService.hasQueuedOwnerMessage(target.id)) {
            undoMerges.remove(assistantId)
            return@withLock MergeResult.Failure("The topic changed before undo")
        }
        chatService.restoreThreadMerge(snapshot.source, snapshot.target)
        undoMerges.remove(assistantId)
        MergeResult.Success(snapshot.handle)
    }

    /**
     * Regenerates from [message]. Sessions are released a few seconds after they go idle, so the
     * segment is loaded first; acting on a released session would operate on an empty conversation.
     */
    suspend fun regenerate(assistantId: Uuid, segmentId: Uuid, message: UIMessage) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.regenerateAtMessage(segmentId, message)
    }

    /** Approves, declines or answers a tool call waiting for the user, loading its segment first. */
    suspend fun answerTool(assistantId: Uuid, segmentId: Uuid, toolCallId: String, approved: Boolean, answer: String? = null, via: me.rerere.ai.ui.ToolDecisionVia = me.rerere.ai.ui.ToolDecisionVia.CARD) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.handleToolApproval(segmentId, toolCallId, approved, answer = answer, via = via)
    }

    /** Decides several pending calls of one reply together; see [ChatService.handleToolApprovals]. */
    suspend fun answerTools(assistantId: Uuid, segmentId: Uuid, toolCallIds: List<String>, approved: Boolean,
        via: me.rerere.ai.ui.ToolDecisionVia = me.rerere.ai.ui.ToolDecisionVia.CARD) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.handleToolApprovals(segmentId, toolCallIds, approved, via = via)
    }

    /** Allows this call and remembers its kind for the partner, so the same step no longer asks. */
    suspend fun alwaysAllowTool(assistantId: Uuid, segmentId: Uuid, toolCallId: String) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.alwaysAllowTool(segmentId, toolCallId)
    }

    /** Allows a calendar call with the time the owner corrected on its card. */
    suspend fun approveToolWithInput(assistantId: Uuid, segmentId: Uuid, toolCallId: String, input: kotlinx.serialization.json.JsonObject) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.approveToolWithInput(segmentId, toolCallId, input)
    }

    fun grantStandingApproval(assistantId: Uuid, key: String) = chatService.grantStandingApproval(assistantId, key)

    suspend fun stop(segmentId: Uuid) = chatService.stopGeneration(segmentId)

    private suspend fun closeSegments(segments: List<Conversation>, ids: Set<Uuid>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val closing = segments.filter { it.id in ids && it.threadClosedAt == 0L }
        closing.forEach { segment ->
            chatService.updateThreadSegment(segment.id) { it.copy(threadClosedAt = now) }
        }
        summarizer.summarizeLater(closing.map { it.id })
    }
}
