package me.ayuilos.miffan.data.thread

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import me.ayuilos.miffan.data.model.MessageNode
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ThreadListSummariesTest {
    private val utc = TimeZone.UTC

    private fun msg(role: MessageRole, minute: Int, vararg parts: UIMessagePart) =
        MessageNode(message = UIMessage(role = role, parts = parts.toList(), createdAt = LocalDateTime(2026, 10, 5, 21, minute)))

    @Test
    fun repliesAfterTheLastVisitAreUnreadButOwnMessagesNever() {
        val readAt = Instant.parse("2026-10-05T21:02:00Z").toEpochMilli()
        val newestFirst = listOf(
            msg(MessageRole.USER, 5, UIMessagePart.Text("还有吗")),
            msg(MessageRole.ASSISTANT, 4, UIMessagePart.Text("芝麻")),
            msg(MessageRole.ASSISTANT, 3, UIMessagePart.Text("奶盖")),
            msg(MessageRole.ASSISTANT, 1, UIMessagePart.Text("团子")),
        )
        val summary = ThreadListSummaries.summarize(newestFirst, readAt, utc)
        assertEquals(2, summary.unread)
        assertEquals("还有吗", summary.preview)
        assertTrue(summary.fromUser)
        assertEquals(Instant.parse("2026-10-05T21:05:00Z"), summary.lastActivity)
    }

    @Test
    fun mediaOnlyMessagesAreDescribedByKind() {
        val image = msg(MessageRole.USER, 1, UIMessagePart.Image("file:///photo.jpg"))
        assertEquals(PreviewKind.IMAGE, ThreadListSummaries.summarize(listOf(image), 0, utc).previewKind)
        assertEquals(PreviewKind.EMPTY, ThreadListSummaries.summarize(emptyList(), 0, utc).previewKind)
    }
    @Test fun eventUsesSummaryWithoutOwnerPrefixOrUnreadIncrement() {
        val event = msg(MessageRole.USER, 5, UIMessagePart.Text("model payload"))
            .copy(origin = me.ayuilos.miffan.data.model.MessageOrigin.Event("debug", "arrived"))
        val reply = msg(MessageRole.ASSISTANT, 4, UIMessagePart.Text("reply"))
        val summary = ThreadListSummaries.summarize(listOf(event, reply), 0, utc)
        assertEquals("arrived", summary.preview)
        assertEquals(PreviewKind.TEXT, summary.previewKind)
        org.junit.Assert.assertFalse(summary.fromUser)
        assertEquals(1, summary.unread)
    }

}
