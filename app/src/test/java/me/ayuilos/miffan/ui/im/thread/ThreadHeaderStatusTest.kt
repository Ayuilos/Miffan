package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.thread.ThreadTimeline
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ThreadHeaderStatusTest {
    private val question = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("在吗")))

    private fun status(vararg replyParts: UIMessagePart, generating: Boolean = true): ThreadHeaderStatus? {
        var segment = Conversation(assistantId = Uuid.random(), messageNodes = emptyList()).appendMessage(question)
        if (replyParts.isNotEmpty()) segment = segment.appendMessage(UIMessage(role = MessageRole.ASSISTANT, parts = replyParts.toList()))
        val generatingIds = if (generating) setOf(segment.id) else emptySet()
        return threadHeaderStatus(ThreadTimeline.build(listOf(segment), generatingSegmentIds = generatingIds), generatingIds)
    }

    @Test
    fun idleShowsNothing() {
        assertNull(status(UIMessagePart.Text("在的"), generating = false))
    }

    @Test
    fun waitingForTheFirstTokenIsThinking() {
        assertEquals(ThreadHeaderStatus.Thinking, status())
    }

    @Test
    fun followsTheNewestPartOfTheStreamingReply() {
        assertEquals(ThreadHeaderStatus.Thinking, status(UIMessagePart.Reasoning("嗯…", finishedAt = null)))
        assertEquals(ThreadHeaderStatus.UsingTools, status(UIMessagePart.Reasoning("查一下"), UIMessagePart.Tool("1", "search", "{}")))
        assertEquals(ThreadHeaderStatus.Typing, status(UIMessagePart.Tool("1", "search", "{}"), UIMessagePart.Text("查到了")))
    }
}
