package me.ayuilos.miffan.data.thread

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageOrigin
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant

/** What the last message of a thread shows in the Chats list when it has no text. */
enum class PreviewKind { TEXT, IMAGE, FILE, EMPTY }

data class ThreadListSummary(
    val preview: String,
    val previewKind: PreviewKind,
    val fromUser: Boolean,
    val lastActivity: Instant?,
    val unread: Int,
)

/**
 * What the Chats list needs from a thread's newest messages, kept so that a new read time can be applied
 * without reading the messages again: everything but the unread count is fixed by the messages.
 */
class ThreadListDigest(
    val preview: String,
    val previewKind: PreviewKind,
    val fromUser: Boolean,
    val lastActivity: Instant?,
    /** Arrival times (epoch millis) of the partner's replies among the digested messages. */
    private val replyArrivals: LongArray,
    /** One of the digested messages holds a tool call the owner has not answered. */
    val hasPendingTool: Boolean,
) {
    fun summary(readAt: Long) = ThreadListSummary(preview, previewKind, fromUser, lastActivity, replyArrivals.count { it > readAt })
}

object ThreadListSummaries {
    /**
     * Summarizes the newest messages of a thread (newest first). Replies that arrived after
     * [readAt] count as unread; the user's own messages never do.
     */
    fun summarize(recentNewestFirst: List<MessageNode>, readAt: Long, zone: TimeZone = TimeZone.currentSystemDefault()): ThreadListSummary =
        digest(recentNewestFirst, zone).summary(readAt)

    fun digest(recentNewestFirst: List<MessageNode>, zone: TimeZone = TimeZone.currentSystemDefault()): ThreadListDigest {
        val visible = recentNewestFirst.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        fun UIMessage.arrivedAt(): Instant = Instant.ofEpochMilli((finishedAt ?: createdAt).toInstant(zone).toEpochMilliseconds())
        val lastNode = visible.firstOrNull()
        val last = lastNode?.message
        val event = lastNode?.origin as? MessageOrigin.Event
        val text = event?.summary ?: last?.listPreviewText().orEmpty()
        val kind = when {
            last == null -> PreviewKind.EMPTY
            text.isNotBlank() -> PreviewKind.TEXT
            last.parts.any { it is UIMessagePart.Image } -> PreviewKind.IMAGE
            last.parts.any { it is UIMessagePart.Document } -> PreviewKind.FILE
            else -> PreviewKind.EMPTY
        }
        return ThreadListDigest(
            preview = text,
            previewKind = kind,
            fromUser = last?.role == MessageRole.USER && lastNode.origin == null,
            lastActivity = last?.arrivedAt(),
            replyArrivals = visible.filter { it.role == MessageRole.ASSISTANT }
                .map { it.message.arrivedAt().toEpochMilli() }.toLongArray(),
            hasPendingTool = recentNewestFirst.any { node -> node.message.parts.any { it is UIMessagePart.Tool && it.isPending } },
        )
    }
}
