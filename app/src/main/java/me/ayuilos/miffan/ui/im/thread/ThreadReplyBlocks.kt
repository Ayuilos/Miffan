package me.ayuilos.miffan.ui.im.thread

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/**
 * A reply in the order things happened: what the user reads (text, pictures, prompts), with the partner's
 * steps between them as they were taken. Steps in a row share one record; computer actions in a row share one card.
 */
internal sealed interface ThreadReplyBlock {
    data class Content(val part: UIMessagePart) : ThreadReplyBlock
    data class Steps(val tools: List<UIMessagePart.Tool>) : ThreadReplyBlock {
        val steps = threadSteps(tools)
    }
    data class Computer(val tools: List<UIMessagePart.Tool>) : ThreadReplyBlock
}

/** [recordSteps] off: the reply's plain steps stay hidden, and do not split the computer actions around them. */
internal fun threadReplyBlocks(parts: List<UIMessagePart>, recordSteps: Boolean): List<ThreadReplyBlock> {
    val blocks = mutableListOf<ThreadReplyBlock>()
    for (part in parts) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) blocks += ThreadReplyBlock.Content(part)
            is UIMessagePart.Image, is UIMessagePart.Document -> blocks += ThreadReplyBlock.Content(part)
            is UIMessagePart.Tool -> when {
                part.isThreadPrompt() || part.isImageGeneration() || part.addedCalendarEvent() != null ->
                    blocks += ThreadReplyBlock.Content(part)
                part.isComputerTool() -> if (part.approvalState !is ToolApprovalState.Denied) {
                    val last = blocks.lastOrNull()
                    if (last is ThreadReplyBlock.Computer) blocks[blocks.lastIndex] = ThreadReplyBlock.Computer(last.tools + part)
                    else blocks += ThreadReplyBlock.Computer(listOf(part))
                }
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
