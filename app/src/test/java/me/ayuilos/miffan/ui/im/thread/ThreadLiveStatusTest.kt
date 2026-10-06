package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.R
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLiveStatusTest {
    @Test
    fun finishedRepliesKeepNoProcessStatus() {
        assertNull(threadLiveStatus(listOf(UIMessagePart.Reasoning("嗯…"), UIMessagePart.Tool("1", "search_web", "{}")), streaming = false))
    }

    @Test
    fun followsWhatTheStreamingReplyIsDoing() {
        assertEquals(R.string.im_thread_status_thinking, threadLiveStatus(emptyList(), streaming = true))
        assertEquals(R.string.im_thread_status_thinking, threadLiveStatus(listOf(UIMessagePart.Reasoning("嗯…", finishedAt = null)), streaming = true))
        assertEquals(R.string.im_thread_tools_running, threadLiveStatus(listOf(UIMessagePart.Tool("1", "search_web", "{}")), streaming = true))
        assertEquals(R.string.im_thread_tools_working, threadLiveStatus(listOf(UIMessagePart.Tool("1", "read_file", "{}")), streaming = true))
    }

    @Test
    fun textOrAPromptCardIsItsOwnFeedback() {
        assertNull(threadLiveStatus(listOf(UIMessagePart.Tool("1", "search_web", "{}"), UIMessagePart.Text("查到了")), streaming = true))
        val waiting = UIMessagePart.Tool("1", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
        assertNull(threadLiveStatus(listOf(waiting), streaming = true))
    }

    @Test
    fun onlyPendingPromptsAndAnsweredQuestionsStayInTheChat() {
        assertTrue(UIMessagePart.Tool("1", "write_file", "{}", approvalState = ToolApprovalState.Pending).isThreadPrompt())
        assertTrue(UIMessagePart.Tool("1", "ask_user", "{}", approvalState = ToolApprovalState.Answered("{}")).isThreadPrompt())
        assertFalse(UIMessagePart.Tool("1", "search_web", "{}").isThreadPrompt())
    }
}
