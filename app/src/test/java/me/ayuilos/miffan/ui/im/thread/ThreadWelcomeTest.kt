package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.ThreadNoticeKind
import me.ayuilos.miffan.data.thread.TimelineItem
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

class ThreadWelcomeTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    private fun message(at: Instant, role: MessageRole = MessageRole.USER) = TimelineItem.Message(
        segmentId = Uuid.random(),
        node = MessageNode.of(UIMessage(role = role, parts = listOf(UIMessagePart.Text("hi")))),
        at = at,
        topicIndex = 0,
        quote = null,
        groupedWithPrevious = false,
        streaming = false,
        canRegenerate = false,
    )

    @Test
    fun aRecentChatOpensOnTheMessages() {
        val last = now - ThreadWelcome.IDLE + Duration.ofMinutes(1)
        assertNull(ThreadWelcome.cutoff(listOf(TimelineItem.DateSeparator(last), message(last)), now))
    }

    @Test
    fun anIdleThreadWelcomesFromItsNewestMessage() {
        val first = now - Duration.ofDays(2)
        val last = now - ThreadWelcome.IDLE
        val timeline = listOf(TimelineItem.DateSeparator(first), message(first), message(last, MessageRole.ASSISTANT))
        assertEquals(last, ThreadWelcome.cutoff(timeline, now))
    }

    @Test
    fun anEmptyThreadHasNoWelcome() {
        assertNull(ThreadWelcome.cutoff(emptyList(), now))
    }

    @Test
    fun messagesOfThisVisitFollowTheHistory() {
        val old = now - Duration.ofDays(1)
        val timeline = listOf(
            TimelineItem.DateSeparator(old), message(old), message(old, MessageRole.ASSISTANT),
            TimelineItem.DateSeparator(now), message(now), TimelineItem.Typing(emptySet()),
        )
        assertEquals(3, ThreadWelcome.historySize(timeline, old))
        assertEquals(3, ThreadWelcome.historySize(timeline.take(3), old))
    }

    @Test
    fun aMemoryNoticeStampedAfterTheReplyStaysInTheHistory() {
        val asked = now - Duration.ofHours(3)
        val replied = asked + Duration.ofSeconds(20)
        val question = message(asked)
        // Memories are extracted after the reply finished, but the notice sits after the question.
        val notice = TimelineItem.Notice(ThreadNotice(
            id = "r1", at = replied + Duration.ofSeconds(5), kind = ThreadNoticeKind.MEMORY, summary = "",
            trigger = question.ref, revisionId = "r1", undoable = true,
        ))
        val timeline = listOf(TimelineItem.DateSeparator(asked), question, notice, message(replied, MessageRole.ASSISTANT))
        val cutoff = ThreadWelcome.cutoff(timeline, now)!!
        assertEquals(timeline.size, ThreadWelcome.historySize(timeline, cutoff))
    }

    @Test
    fun relativeTimeLastsThreeDays() {
        assertTrue(ThreadWelcome.isRelative(now - Duration.ofDays(3) + Duration.ofMinutes(1), now))
        assertFalse(ThreadWelcome.isRelative(now - Duration.ofDays(3), now))
    }
}
