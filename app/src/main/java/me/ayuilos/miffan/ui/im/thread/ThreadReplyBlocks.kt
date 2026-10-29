package me.ayuilos.miffan.ui.im.thread

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/**
 * A reply in the order things happened: what the user reads (text, pictures, prompts), with the partner's
 * steps between them as they were taken. Steps in a row share one record; the computer work of the whole reply
 * (computer actions and the steps in the partner's own mini box) shares one card, where it first appears.
 */
internal sealed interface ThreadReplyBlock {
    data class Content(val part: UIMessagePart) : ThreadReplyBlock
    data class Steps(val tools: List<UIMessagePart.Tool>) : ThreadReplyBlock {
        val steps = threadSteps(tools)
    }
    data class Computer(val tools: List<UIMessagePart.Tool>) : ThreadReplyBlock
}

/**
 * [recordSteps] off: the reply's plain steps stay hidden, and do not split the computer actions around them.
 * While the reply is [streaming], a computer action waiting for the owner joins the computer card, which asks
 * in place; otherwise it stays a prompt of its own.
 */
internal fun threadReplyBlocks(parts: List<UIMessagePart>, recordSteps: Boolean, streaming: Boolean = false): List<ThreadReplyBlock> {
    val blocks = mutableListOf<ThreadReplyBlock>()
    val hasComputer = parts.any {
        it is UIMessagePart.Tool && it.isComputerTool() && it.approvalState !is ToolApprovalState.Denied
    }
    fun addComputer(tool: UIMessagePart.Tool) {
        val index = blocks.indexOfFirst { it is ThreadReplyBlock.Computer }
        if (index >= 0) blocks[index] = ThreadReplyBlock.Computer((blocks[index] as ThreadReplyBlock.Computer).tools + tool)
        else blocks += ThreadReplyBlock.Computer(listOf(tool))
    }
    for (part in parts) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) blocks += ThreadReplyBlock.Content(part)
            is UIMessagePart.Image, is UIMessagePart.Document -> blocks += ThreadReplyBlock.Content(part)
            is UIMessagePart.Tool -> when {
                streaming && part.isComputerTool() && part.approvalState is ToolApprovalState.Pending -> addComputer(part)
                // Answered questions are part of the card (a declined one shows as how the record ends), not lines of their own.
                part.isComputerTool() && part.approvalState !is ToolApprovalState.Pending &&
                    (part.approvalState !is ToolApprovalState.Denied || hasComputer) -> addComputer(part)
                part.isThreadPrompt() || part.isImageGeneration() || part.addedCalendarEvent() != null ->
                    blocks += ThreadReplyBlock.Content(part)
                // What it does in its own mini box is computer work too, once there is a record to put it in.
                part.isMiniBoxStep() && (recordSteps || hasComputer) -> addComputer(part)
                recordSteps && threadSteps(listOf(part)).isNotEmpty() -> {
                    val last = blocks.lastOrNull()
                    if (last is ThreadReplyBlock.Steps) blocks[blocks.lastIndex] = ThreadReplyBlock.Steps(last.tools + part)
                    else blocks += ThreadReplyBlock.Steps(listOf(part))
                }
            }
            else -> Unit
        }
    }
    return blocks
}
