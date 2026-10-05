package me.ayuilos.miffan.data.thread

import android.util.Log
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.service.ChatService
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ThreadService"

/** Sends messages into an assistant's IM thread, choosing the segment for each message. */
class ThreadService(
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val router: SegmentRouter,
    private val summarizer: SegmentSummarizer,
) {
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
    ): Uuid {
        val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId)
            ?: error("Assistant not found: $assistantId")
        val forced = topicSegmentId ?: replyTo?.conversationId
        val segmentId = if (forced != null) {
            forced
        } else {
            val decision = router.route(RouteRequest(assistant, content, segments, Instant.now()))
            Log.i(TAG, "route ${assistant.id}: $decision")
            when (decision) {
                is RouteDecision.Existing -> decision.segmentId
                is RouteDecision.New -> {
                    closeSegments(segments, decision.closeSegmentIds)
                    Uuid.random()
                }
            }
        }
        chatService.openThreadSegment(segmentId, assistantId)
        chatService.sendMessage(segmentId, content, replyTo = replyTo, messageId = messageId)
        return segmentId
    }

    /** Forces the next message into a new segment ("换个话题"). */
    suspend fun closeAll(segments: List<Conversation>) {
        closeSegments(segments, segments.filter { it.threadClosedAt == 0L }.mapTo(HashSet()) { it.id })
    }

    fun regenerate(segmentId: Uuid, message: UIMessage) = chatService.regenerateAtMessage(segmentId, message)

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
