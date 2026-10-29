package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.thread.ThreadTimeline
import me.ayuilos.miffan.data.thread.TimelineItem
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ThreadHeaderStatusTest {
    private val question = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("在吗")))

    private fun status(
        vararg replyParts: UIMessagePart,
        generating: Boolean = true,
        connected: Boolean = true,
        online: Boolean = true,
    ): ThreadHeaderStatus? {
        var segment = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()).appendMessage(question)
        if (replyParts.isNotEmpty()) segment = segment.appendMessage(UIMessage(role = MessageRole.ASSISTANT, parts = replyParts.toList()))
        val generatingIds = if (generating) setOf(segment.id) else emptySet()
        val connection = ThreadConnection(if (connected) generatingIds else emptySet(), online)
        return threadHeaderStatus(ThreadTimeline.build(listOf(segment), generatingSegmentIds = generatingIds), generatingIds, connection)
    }

    @Test
    fun idleShowsNothing() {
        assertNull(status(UIMessagePart.Text("在的"), generating = false))
    }

    @Test
    fun aMessageBeingRoutedIsStillConnecting() {
        val typing = listOf(TimelineItem.Typing(emptySet()))
        assertEquals(ThreadHeaderStatus.Connecting, threadHeaderStatus(typing, emptySet(), ThreadConnection()))
        assertEquals(ThreadHeaderStatus.Offline, threadHeaderStatus(typing, emptySet(), ThreadConnection(online = false)))
    }

    @Test
    fun waitingForTheProviderIsConnectingUntilItAnswers() {
        assertEquals(ThreadHeaderStatus.Connecting, status(connected = false))
        assertEquals(ThreadHeaderStatus.Thinking, status(connected = true))
    }

    @Test
    fun noNetworkOnlyMattersBeforeTheProviderAnswers() {
        assertEquals(ThreadHeaderStatus.Offline, status(connected = false, online = false))
        assertEquals(ThreadHeaderStatus.Thinking, status(connected = true, online = false))
        assertEquals(ThreadHeaderStatus.Typing, status(UIMessagePart.Text("在的"), connected = false, online = false))
    }

    @Test
    fun reasoningMeansTheProviderAnsweredEvenWithoutTheSignal() {
        assertEquals(ThreadHeaderStatus.Thinking, status(UIMessagePart.Reasoning("嗯…", finishedAt = null), connected = false))
    }

    @Test
    fun followsTheNewestPartOfTheStreamingReply() {
        assertEquals(ThreadHeaderStatus.Thinking, status(UIMessagePart.Reasoning("嗯…", finishedAt = null)))
        assertEquals(ThreadHeaderStatus.UsingTools, status(UIMessagePart.Reasoning("查一下"), UIMessagePart.Tool("1", "search", "{}")))
        assertEquals(ThreadHeaderStatus.Typing, status(UIMessagePart.Tool("1", "search", "{}"), UIMessagePart.Text("查到了")))
    }
}
