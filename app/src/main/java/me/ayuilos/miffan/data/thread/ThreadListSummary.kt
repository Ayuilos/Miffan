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

object ThreadListSummaries {
    /**
     * Summarizes the newest messages of a thread (newest first). Replies that arrived after
     * [readAt] count as unread; the user's own messages never do.
     */
    fun summarize(recentNewestFirst: List<MessageNode>, readAt: Long, zone: TimeZone = TimeZone.currentSystemDefault()): ThreadListSummary {
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
        return ThreadListSummary(
            preview = text,
            previewKind = kind,
            fromUser = last?.role == MessageRole.USER && lastNode.origin == null,
            lastActivity = last?.arrivedAt(),
            unread = visible.count { it.role == MessageRole.ASSISTANT && it.message.arrivedAt().toEpochMilli() > readAt },
        )
    }
}
