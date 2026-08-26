package me.ayuilos.miffan.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `pending approvals keep every thinking step visible`() {
        val steps = (1..4).map { index ->
            toolStep(
                id = "shell-$index",
                state = ToolApprovalState.Pending,
            )
        }

        assertEquals(4, steps.pendingToolApprovalCount())
        assertEquals(4, steps.pendingWorkspaceShellApprovalCount())
        assertEquals(4, steps.approvalAwareCollapsedVisibleCount())
    }

    @Test
    fun `resolved tool steps keep the normal collapsed preview`() {
        val steps = (1..4).map { index ->
            toolStep(
                id = "shell-$index",
                state = ToolApprovalState.Approved,
            )
        }

        assertEquals(0, steps.pendingToolApprovalCount())
        assertEquals(2, steps.approvalAwareCollapsedVisibleCount())
    }

    private fun toolStep(
        id: String,
        state: ToolApprovalState,
    ): ThinkingStep.ToolStep = ThinkingStep.ToolStep(
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = WORKSPACE_SHELL_TOOL_NAME,
            input = "{}",
            approvalState = state,
        )
    )
}
