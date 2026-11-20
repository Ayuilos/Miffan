package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.rerere.ai.ui.ToolApprovalRecord
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.ToolDecisionVia
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLiveStatusTest {
    @Test
    fun finishedRepliesKeepNoProcessStatus() {
        assertNull(threadLiveStatus(listOf(UIMessagePart.Reasoning("嗯…"), UIMessagePart.Tool("1", "search_web", "{}")), streaming = false, connected = true, online = true))
    }

    @Test
    fun followsWhatTheStreamingReplyIsDoing() {
        assertEquals(R.string.im_thread_status_thinking, threadLiveStatus(emptyList(), streaming = true, connected = true, online = true))
        assertEquals(R.string.im_thread_status_thinking, threadLiveStatus(listOf(UIMessagePart.Reasoning("嗯…", finishedAt = null)), streaming = true, connected = true, online = true))
        assertEquals(R.string.im_thread_tools_running, threadLiveStatus(listOf(UIMessagePart.Tool("1", "search_web", "{}")), streaming = true, connected = true, online = true))
        assertEquals(R.string.im_thread_tools_working, threadLiveStatus(listOf(UIMessagePart.Tool("1", "read_file", "{}")), streaming = true, connected = true, online = true))
    }

    @Test
    fun saysConnectingOrOfflineUntilTheProviderAnswers() {
        assertEquals(R.string.im_thread_status_connecting, threadLiveStatus(emptyList(), streaming = true, connected = false, online = true))
        assertEquals(R.string.im_thread_status_offline, threadLiveStatus(emptyList(), streaming = true, connected = false, online = false))
        assertEquals(R.string.im_thread_status_thinking, threadLiveStatus(emptyList(), streaming = true, connected = true, online = false))
        assertEquals(R.string.im_thread_status_thinking,
            threadLiveStatus(listOf(UIMessagePart.Reasoning("嗯…", finishedAt = null)), streaming = true, connected = false, online = false))
        assertEquals(R.string.im_thread_tools_working,
            threadLiveStatus(listOf(UIMessagePart.Tool("1", "read_file", "{}")), streaming = true, connected = false, online = false))
        assertNull(threadLiveStatus(listOf(UIMessagePart.Text("在的")), streaming = true, connected = false, online = false))
    }

    @Test
    fun textOrAPromptCardIsItsOwnFeedback() {
        assertNull(threadLiveStatus(listOf(UIMessagePart.Tool("1", "search_web", "{}"), UIMessagePart.Text("查到了")), streaming = true, connected = true, online = true))
        val waiting = UIMessagePart.Tool("1", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
        assertNull(threadLiveStatus(listOf(waiting), streaming = true, connected = true, online = true))
    }

    @Test
    fun onlyPendingPromptsAndAnsweredQuestionsStayInTheChat() {
        assertTrue(UIMessagePart.Tool("1", "write_file", "{}", approvalState = ToolApprovalState.Pending).isThreadPrompt())
        assertTrue(UIMessagePart.Tool("1", "ask_user", "{}", approvalState = ToolApprovalState.Answered("{}")).isThreadPrompt())
        assertFalse(UIMessagePart.Tool("1", "search_web", "{}").isThreadPrompt())
    }

    @Test
    fun settledPermissionCardsLeaveARecord() {
        fun settled(name: String, state: ToolApprovalState, decision: ToolDecision) = UIMessagePart.Tool("1", name, "{}",
            approvalState = state, approvalRecord = ToolApprovalRecord(1L, 2L, decision, ToolDecisionVia.CARD))
        assertTrue(settled("request_web_search", ToolApprovalState.Approved, ToolDecision.ALLOWED).isThreadPrompt())
        assertTrue(settled("computer_click", ToolApprovalState.Denied(""), ToolDecision.DECLINED).isThreadPrompt())
        assertTrue(settled("workspace_shell", ToolApprovalState.Denied("stopped"), ToolDecision.CANCELLED).isThreadPrompt())
        // Never asked, so nothing to record; answered questions keep their own presentation.
        assertFalse(settled("search_web", ToolApprovalState.Auto, ToolDecision.AUTO_ALLOWED).isSettledPermission())
        assertFalse(settled("ask_user", ToolApprovalState.Answered("{}"), ToolDecision.ANSWERED).isSettledPermission())
        assertFalse(UIMessagePart.Tool("1", "write_file", "{}", approvalState = ToolApprovalState.Approved).isSettledPermission())
    }

    @Test
    fun partnerTurnStatusFollowsToolsAndApprovalWithoutMisreportingConnecting() {
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_status_connecting, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(emptyList(), connected = false, online = true),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_status_offline, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(emptyList(), connected = false, online = false),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_status_thinking, AssistantGenerationPhase.Reasoning),
            partnerTurnLiveStatus(emptyList(), connected = true, online = false),
        )

        val pending = UIMessagePart.Tool("1", "workspace_terminal", "{}", approvalState = ToolApprovalState.Pending)
        assertNull(partnerTurnLiveStatus(listOf(UIMessagePart.Text("需要确认"), pending), connected = false, online = true))

        assertEquals(
            PartnerLiveStatus(R.string.im_thread_tools_running, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(listOf(UIMessagePart.Tool("1", "search_web", "{}")), connected = false, online = true),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_tools_working, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(listOf(UIMessagePart.Tool("1", "workspace_read_file", "{}")), connected = false, online = true),
        )

        val executed = UIMessagePart.Tool("1", "workspace_read_file", "{}", output = listOf(UIMessagePart.Text("ok")))
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_tools_working, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(listOf(executed), connected = false, online = true),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_status_offline, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(listOf(executed), connected = false, online = false),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_status_thinking, AssistantGenerationPhase.Reasoning),
            partnerTurnLiveStatus(listOf(executed), connected = true, online = true),
        )

        assertEquals(
            PartnerLiveStatus(R.string.im_thread_typing, AssistantGenerationPhase.Responding),
            partnerTurnLiveStatus(listOf(UIMessagePart.Text("写好了")), connected = true, online = true),
        )
        assertEquals(
            PartnerLiveStatus(R.string.im_thread_tools_running, AssistantGenerationPhase.Waiting),
            partnerTurnLiveStatus(listOf(UIMessagePart.Text("先查一下"), UIMessagePart.Tool("2", "search_web", "{}")), connected = false, online = true),
        )
    }
}
