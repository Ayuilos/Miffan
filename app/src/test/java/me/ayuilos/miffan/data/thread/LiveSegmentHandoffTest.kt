package me.ayuilos.miffan.data.thread

import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class LiveSegmentHandoffTest {
    private val question = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("1001 是质数吗")))
    private val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("不是，7 × 11 × 13")))
    private val asked = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()).appendMessage(question)
    private val answered = asked.appendMessage(reply)
    private val start = Instant.parse("2026-10-06T12:00:00Z")

    private fun newest(handoff: LiveSegmentHandoff) = handoff.merged.single().currentMessages.last()

    @Test
    fun keepsTheFinishedReplyUntilTheStoredCopyHasIt() {
        var handoff = LiveSegmentHandoff().next(stored = listOf(asked), live = listOf(answered), now = start)
        // The job ended, but the database has not emitted the saved reply yet.
        handoff = handoff.next(stored = listOf(asked), live = emptyList(), now = start.plusMillis(300))
        assertEquals(reply.id, newest(handoff).id)
        handoff = handoff.next(stored = listOf(answered), live = emptyList(), now = start.plusMillis(600))
        assertEquals(reply.id, newest(handoff).id)
        // Once caught up the stored copy rules, so later edits show.
        val edited = answered.copy(messageNodes = answered.messageNodes.dropLast(1))
        handoff = handoff.next(stored = listOf(edited), live = emptyList(), now = start.plusMillis(900))
        assertEquals(question.id, newest(handoff).id)
    }

    @Test
    fun aStoredCopyThatNeverCatchesUpIsDroppedAfterTheHold() {
        var handoff = LiveSegmentHandoff(holdMillis = 1_000).next(stored = listOf(asked), live = listOf(answered), now = start)
        handoff = handoff.next(stored = listOf(asked), live = emptyList(), now = start.plusMillis(500))
        assertEquals(reply.id, newest(handoff).id)
        handoff = handoff.next(stored = listOf(asked), live = emptyList(), now = start.plusMillis(1_500))
        assertEquals(question.id, newest(handoff).id)
    }

    @Test
    fun aNewSegmentStaysVisibleBeforeItIsStored() {
        var handoff = LiveSegmentHandoff().next(stored = emptyList(), live = listOf(answered), now = start)
        handoff = handoff.next(stored = emptyList(), live = emptyList(), now = start.plusMillis(200))
        assertEquals(reply.id, newest(handoff).id)
    }
}
