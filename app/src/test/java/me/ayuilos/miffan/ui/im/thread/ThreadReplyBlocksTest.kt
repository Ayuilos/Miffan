package me.ayuilos.miffan.ui.im.thread

import me.rerere.ai.ui.ToolApprovalRecord
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.ToolDecisionVia
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadReplyBlocksTest {
    private fun tool(name: String, id: String = name, state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(id, name, "{}", listOf(UIMessagePart.Text("""{"ok":true}""")), state)

    private fun List<ThreadReplyBlock>.shape() = map { block ->
        when (block) {
            is ThreadReplyBlock.Content -> (block.part as? UIMessagePart.Text)?.text ?: (block.part as UIMessagePart.Tool).toolName
            is ThreadReplyBlock.Steps -> "steps:" + block.tools.joinToString(",") { it.toolCallId }
            is ThreadReplyBlock.Computer -> "computer:" + block.tools.joinToString(",") { it.toolCallId }
        }
    }

    @Test
    fun keepsComputerActionsAndStepsWhereTheyHappened() {
        val blocks = threadReplyBlocks(listOf(
            UIMessagePart.Text("我看一下屏幕"),
            tool("computer_get_desktop_state", "look"),
            UIMessagePart.Reasoning("…"),
            tool("computer_click", "click"),
            UIMessagePart.Text("打开了，接下来写文件"),
            tool("workspace_write_file", "write"),
            tool("workspace_shell", "run"),
            UIMessagePart.Text(" "),
            UIMessagePart.Text("好了"),
        ), recordSteps = true)
        assertEquals(listOf("我看一下屏幕", "computer:look,click", "打开了，接下来写文件", "steps:write,run", "好了"), blocks.shape())
    }

    @Test
    fun aReplyHasOneComputerCardWhereverItsActionsHappened() {
        val parts = listOf(tool("computer_click", "a"), tool("search_web", "s"), tool("computer_click", "b"))
        assertEquals(listOf("computer:a,b"), threadReplyBlocks(parts, recordSteps = false).shape())
        assertEquals(listOf("computer:a,b", "steps:s"), threadReplyBlocks(parts, recordSteps = true).shape())
        val spoken = listOf(tool("computer_click", "a"), UIMessagePart.Text("再试一次"), tool("computer_click", "b"))
        assertEquals(listOf("computer:a,b", "再试一次"), threadReplyBlocks(spoken, recordSteps = false).shape())
    }

    @Test
    fun theOwnersQuestionsAndAnswersAllJoinTheComputerCard() {
        val allowed = tool("computer_click", "allowed").copy(
            approvalState = ToolApprovalState.Approved,
            approvalRecord = ToolApprovalRecord(decidedAt = 1, decision = ToolDecision.ALLOWED, via = ToolDecisionVia.CARD))
        val declined = tool("computer_click", "denied", ToolApprovalState.Denied("no")).copy(
            approvalRecord = ToolApprovalRecord(decidedAt = 2, decision = ToolDecision.DECLINED, via = ToolDecisionVia.CARD))
        val blocks = threadReplyBlocks(listOf(
            tool("computer_click", "a"),
            tool("computer_type_text", "pending", ToolApprovalState.Pending),
            allowed,
            declined,
        ), recordSteps = false)
        assertEquals(listOf("computer:a,pending,allowed,denied"), blocks.shape())
    }

    @Test
    fun aDeclinedActionAloneLeavesNoCardOfItsOwn() {
        val blocks = threadReplyBlocks(listOf(tool("computer_click", "denied", ToolApprovalState.Denied("no"))), recordSteps = false)
        assertEquals(emptyList<String>(), blocks.shape())
    }
}
