package me.ayuilos.miffan.data.ai.transformers

import me.ayuilos.miffan.data.revision.RevisionService
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val UNDONE_CHANGE_NOTE =
    "[Later: the owner undid this change with the undo button on its card. None of it is in effect any more; " +
        "everything it changed is back to how it was before this call. Do not describe it as applied.]"

/**
 * Tells the partner which of its changes the owner undid afterwards. Undo happens on the change's card, outside the
 * chat, so without this the partner still reads its own "changed" result and believes the change stands. The note
 * follows the current state: redoing the change takes it away again. Nothing is stored; only the request changes.
 */
class UndoneChangesTransformer(
    private val undone: suspend (toolCallIds: List<String>) -> Set<String>,
) : InputMessageTransformer {
    constructor(revisions: RevisionService) : this(revisions::undoneToolCalls)

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val ids = messages.flatMap { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isExecuted }.map { it.toolCallId }
        }
        if (ids.isEmpty()) return messages
        val undoneIds = runCatching { undone(ids) }.getOrElse { return messages }
        if (undoneIds.isEmpty()) return messages
        return messages.map { message ->
            if (message.parts.none { it is UIMessagePart.Tool && it.toolCallId in undoneIds }) return@map message
            message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isExecuted && part.toolCallId in undoneIds) {
                    part.copy(output = part.output + UIMessagePart.Text(UNDONE_CHANGE_NOTE))
                } else {
                    part
                }
            })
        }
    }
}
