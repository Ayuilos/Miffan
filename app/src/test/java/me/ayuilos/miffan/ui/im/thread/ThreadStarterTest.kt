package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.thread.PARTNER_ADDED_EVENT
import me.ayuilos.miffan.data.thread.TimelineItem
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ThreadStarterTest {
    private val segment = Uuid.random()
    private val at = Instant.parse("2026-10-25T12:00:00Z")

    private fun message(role: MessageRole, text: String, origin: MessageOrigin? = null, streaming: Boolean = false) = TimelineItem.Message(
        segmentId = segment,
        node = MessageNode.of(UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))).copy(origin = origin),
        at = at,
        topicIndex = 0,
        quote = null,
        groupedWithPrevious = false,
        streaming = streaming,
        canRegenerate = false,
    )

    private fun event(kind: String = PARTNER_ADDED_EVENT): TimelineItem.Event {
        val origin = MessageOrigin.Event(kind = kind, summary = "You added Miffan")
        return TimelineItem.Event(segment, MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("say hi"))))
            .copy(origin = origin), at, origin)
    }

    @Test
    fun theQuizHangsUnderTheNewPartnersHelloUntilTheOwnerSpeaks() {
        val hello = message(MessageRole.ASSISTANT, "Hi, I'm Miffan!")
        val starter = threadStarter(listOf(event(), hello))
        assertEquals(hello.message.id, starter?.greetingId)
        assertNull(starter?.reply)
    }

    @Test
    fun theOwnersFirstWordsAfterTheHelloSettleIt() {
        val hello = message(MessageRole.ASSISTANT, "Hi!")
        val timeline = listOf(event(), hello, message(MessageRole.USER, " Tell me about yourself \n"),
            message(MessageRole.ASSISTANT, "Sure"), message(MessageRole.USER, "later"))
        assertEquals("Tell me about yourself", threadStarter(timeline)?.reply)
    }

    @Test
    fun anotherPartnersWordsAreNotTheOwnersReply() {
        val partner = MessageOrigin.Partner(assistantId = Uuid.random(), exchangeId = Uuid.random())
        val timeline = listOf(event(), message(MessageRole.ASSISTANT, "Hi!"), message(MessageRole.USER, "hello from a friend", partner))
        assertNull(threadStarter(timeline)?.reply)
    }

    @Test
    fun noQuizWhileTheHelloIsStillComingOrForOtherEvents() {
        assertNull(threadStarter(listOf(event(), message(MessageRole.ASSISTANT, "Hi", streaming = true))))
        assertNull(threadStarter(listOf(event("partner_replied"), message(MessageRole.ASSISTANT, "Hi"))))
        assertNull(threadStarter(listOf(message(MessageRole.ASSISTANT, "Hi"))))
    }
}
