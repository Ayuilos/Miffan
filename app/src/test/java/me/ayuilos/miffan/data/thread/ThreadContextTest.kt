package me.ayuilos.miffan.data.thread

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.ayuilos.miffan.data.db.dao.ThreadSegmentDigest
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid

class ThreadContextTest {
    private val assistantId = Uuid.random()
    private val currentId = Uuid.random()
    private val now = Instant.parse("2026-10-05T21:30:00Z")

    private fun message(text: String, minute: Int, role: MessageRole = MessageRole.USER, day: Int = 5) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), createdAt = LocalDateTime(2026, 10, day, 21, minute))

    private fun segment(vararg messages: UIMessage): Conversation =
        messages.fold(Conversation(assistantId = assistantId, messageNodes = emptyList())) { segment, message ->
            segment.appendMessage(message)
        }

    private fun format(vararg segments: Conversation) =
        ThreadContext.formatRecentMessages(segments.toList(), assistantId, currentId, now, ZoneOffset.UTC)

    private fun String.messageLines() = lineSequence().filter { it.startsWith("- (") }.toList()

    @Test
    fun mergesSegmentsAndKeepsOnlyTheNewestSixMessagesInChronologicalOrder() {
        val a = segment(message("one", 1), message("four", 4, MessageRole.ASSISTANT), message("seven", 7))
        val b = segment(message("two", 2), message("five", 5, MessageRole.ASSISTANT), message("eight", 8))
        val c = segment(message("three", 3), message("six", 6, MessageRole.ASSISTANT))

        val text = format(b, a, c)!!

        assertTrue(text.startsWith("<recent_messages>"))
        assertTrue(text.endsWith("</recent_messages>"))
        assertEquals(listOf(
            "- (2026-10-05 21:03) User: three",
            "- (2026-10-05 21:04) You: four",
            "- (2026-10-05 21:05) You: five",
            "- (2026-10-05 21:06) You: six",
            "- (2026-10-05 21:07) User: seven",
            "- (2026-10-05 21:08) User: eight",
        ), text.messageLines())
    }

    @Test
    fun recentMessagesDoNotNeedASummaryOrAClosedSegment() {
        val open = segment(message("open topic", 20))
        val closed = segment(message("awaiting summary", 21)).copy(threadClosedAt = 1)
        assertEquals(2, format(open, closed)!!.messageLines().size)
    }

    @Test
    fun assistantRepliesUseTheirArrivalTimeLikeTheTimeline() {
        val reply = message("arrived later", 10, MessageRole.ASSISTANT, day = 4)
            .copy(finishedAt = LocalDateTime(2026, 10, 5, 21, 25))
        assertEquals(listOf(
            "- (2026-10-05 21:20) User: question",
            "- (2026-10-05 21:25) You: arrived later",
        ), format(segment(reply), segment(message("question", 20)))!!.messageLines())
    }

    @Test
    fun excludesMessagesOlderThanTwentyFourHoursAndFutureMessages() {
        val history = segment(
            message("too old", 29, day = 4),
            message("at cutoff", 30, day = 4),
            message("recent", 20),
            message("future", 31),
        )
        val text = format(history)!!
        assertFalse(text.contains("too old"))
        assertFalse(text.contains("future"))
        assertEquals(2, text.messageLines().size)
        assertTrue(text.contains("at cutoff"))
        assertTrue(text.contains("recent"))
    }

    @Test
    fun excludesCurrentSegmentOtherAssistantsAndPartnerPerspectives() {
        val current = segment(message("current", 20)).copy(id = currentId)
        val otherAssistant = segment(message("other assistant", 20)).copy(assistantId = Uuid.random())
        val perspective = segment(message("private perspective", 20)).copy(chatId = Uuid.random())
        assertNull(format(current, otherAssistant, perspective))
    }

    @Test
    fun includesOnlySelectedVisibleOwnerAndAssistantText() {
        val owner = message("owner", 20)
        val alternative = message("unselected", 21)
        val selected = message("selected", 22, MessageRole.ASSISTANT)
        var history = segment(owner, alternative)
        history = history.addNodeAndSelect(
            MessageNode(message = selected, parentId = history.currentMessageNodes.first().id)
        )
            .appendMessage(message("partner", 23), origin = MessageOrigin.Partner(Uuid.random(), Uuid.random()))
            .appendMessage(message("event", 24), origin = MessageOrigin.Event("test", "notice"))
            .appendMessage(message("system", 25, MessageRole.SYSTEM))
            .appendMessage(message("tool", 26, MessageRole.TOOL))
            .appendMessage(message("   ", 27))

        val text = format(history)!!

        assertEquals(listOf("- (2026-10-05 21:20) User: owner", "- (2026-10-05 21:22) You: selected"), text.messageLines())
    }

    @Test
    fun keepsOnlyTextPartsAndTruncatesEachMessage() {
        val long = message("x".repeat(900), 20).copy(parts = listOf(
            UIMessagePart.Text("x".repeat(450)),
            UIMessagePart.Text("y".repeat(450)),
            UIMessagePart.Image(url = "hidden-image"),
        ))
        val text = format(segment(long))!!
        val body = text.substringAfter("User: ").substringBefore("\n</recent_messages>")
        assertEquals(("x".repeat(450) + "\n" + "y".repeat(450)).take(800) + "…", body)
        assertFalse(text.contains("hidden-image"))
    }

    @Test
    fun noRecentTextProducesNoSection() {
        assertNull(format())
        assertNull(format(segment(message("old", 20, day = 4))))
        assertNull(format(segment(message("", 20))))
    }

    @Test
    fun buildLoadsBoundedOtherSegmentsEvenWhenNoSummariesExist() = runBlocking {
        val repository = mockk<ConversationRepository>()
        coEvery { repository.getThreadDigests(assistantId, currentId, 3) } returns emptyList()
        coEvery { repository.getRecentThreadSegments(assistantId, currentId, 3) } returns
            listOf(segment(UIMessage.user("just discussed")))

        val text = ThreadContext.build(repository, assistantId, currentId)!!

        assertTrue(text.startsWith("<recent_messages>"))
        assertTrue(text.contains("just discussed"))
        coVerify(exactly = 1) { repository.getRecentThreadSegments(assistantId, currentId, 3) }
        val digest = ThreadSegmentDigest("a", "topic", "earlier summary", now.toEpochMilli())
        coEvery { repository.getThreadDigests(assistantId, currentId, 3) } returns listOf(digest)
        val combined = ThreadContext.build(repository, assistantId, currentId)!!
        assertTrue(combined.contains("<earlier_conversations>"))
        assertTrue(combined.contains("<recent_messages>"))
        coEvery { repository.getRecentThreadSegments(assistantId, currentId, 3) } returns emptyList()
        assertFalse(ThreadContext.build(repository, assistantId, currentId)!!.contains("<recent_messages>"))
        coEvery { repository.getThreadDigests(assistantId, currentId, 3) } returns emptyList()
        assertNull(ThreadContext.build(repository, assistantId, currentId))
    }
}
