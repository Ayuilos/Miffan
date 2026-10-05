package me.ayuilos.miffan.data.thread

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.service.ChatService
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
) {
    /** Serializes routing so each decision sees the topics that earlier messages just opened. */
    private val routeMutex = Mutex()

    /** Topics opened in this process, by segment id, until the stored segments include them. */
    private val recentTopics = ConcurrentHashMap<Uuid, RecentTopic>()

    private data class RecentTopic(val assistantId: Uuid, val label: String, val at: Instant)

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
    ): Uuid {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?: error("Assistant not found: $assistantId")
        val forced = topicSegmentId ?: replyTo?.conversationId
        return routeMutex.withLock {
            val now = Instant.now()
            val known = segments.mapTo(HashSet()) { it.id }
            recentTopics.entries.removeAll { (id, topic) -> id in known || now.toEpochMilli() - topic.at.toEpochMilli() > RECENT_TOPIC_MILLIS }
            // A topic opened moments ago may not be saved yet; offer it as a candidate by its first message.
            val unsaved = recentTopics.filter { it.value.assistantId == assistantId }.map { (id, topic) ->
                Conversation(id = id, assistantId = assistantId, title = topic.label, messageNodes = emptyList(), createAt = topic.at, updateAt = topic.at)
            }
            val candidates = (segments + unsaved).sortedByDescending { it.updateAt }
            val segmentId = if (forced != null) {
                forced
            } else {
                val decision = router.route(RouteRequest(assistant, content, candidates, now))
                Log.i(TAG, "route ${assistant.id}: $decision")
                when (decision) {
                    is RouteDecision.Existing -> decision.segmentId
                    is RouteDecision.New -> {
                        closeSegments(segments, decision.closeSegmentIds)
                        decision.closeSegmentIds.forEach { recentTopics.remove(it) }
                        Uuid.random()
                    }
                }
            }
            if (segmentId !in known) {
                val label = recentTopics[segmentId]?.label
                    ?: content.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.take(120)
                recentTopics[segmentId] = RecentTopic(assistantId, label, now)
            }
            deliver(assistantId, segmentId, content, replyTo, messageId, createdAt)
            segmentId
        }
    }

    private suspend fun deliver(assistantId: Uuid, segmentId: Uuid, content: List<UIMessagePart>, replyTo: MessageRef?, messageId: Uuid, createdAt: LocalDateTime?) {
        chatService.openThreadSegment(segmentId, assistantId)
        // Like a messenger, a message to a topic that is still replying waits its turn instead of
        // cancelling that reply. Sending again also resumes a queue paused by an earlier failure.
        chatService.sendMessage(segmentId, content, immediately = false, replyTo = replyTo, messageId = messageId, createdAt = createdAt)
        chatService.resumeMessageQueue(segmentId)
    }

    /** Forces the next message into a new segment ("换个话题"). */
    suspend fun closeAll(segments: List<Conversation>) {
        closeSegments(segments, segments.filter { it.threadClosedAt == 0L }.mapTo(HashSet()) { it.id })
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
    suspend fun answerTool(assistantId: Uuid, segmentId: Uuid, toolCallId: String, approved: Boolean, answer: String? = null) {
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.handleToolApproval(segmentId, toolCallId, approved, answer = answer)
    }

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
