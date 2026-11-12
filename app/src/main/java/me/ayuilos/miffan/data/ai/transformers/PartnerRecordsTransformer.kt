package me.ayuilos.miffan.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.ayuilos.miffan.data.ai.tools.MY_RECORDS_TOOL
import me.ayuilos.miffan.data.partner.OutsideEvent
import me.ayuilos.miffan.data.partner.PartnerRecords
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/**
 * Before each owner message, tells the partner what happened to it outside the chat since its reply before that
 * message: the owner changing its profile or standing approvals, another partner changing it, the owner undoing one
 * of its changes. Each message gets the events of its own window, so earlier turns read the same every time (the
 * prompt cache keeps them) and the partner still knows on later turns. Nothing is stored.
 */
class PartnerRecordsTransformer(
    private val events: suspend (assistantId: Uuid, calls: List<UIMessagePart.Tool>, after: Instant) -> List<OutsideEvent>,
    private val time: (Instant) -> String,
) : InputMessageTransformer {
    constructor(records: PartnerRecords) : this(records::outsideEvents, records::time)

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        withOutsideEvents(ctx.assistant.id, messages)

    internal suspend fun withOutsideEvents(assistantId: Uuid, messages: List<UIMessage>): List<UIMessage> {
        val zone = TimeZone.currentSystemDefault()
        fun UIMessage.at(): Instant = createdAt.toInstant(zone).toJavaInstant()
        // Each owner message after a reply: the events between that reply and the message.
        val windows = messages.indices.mapNotNull { index ->
            val message = messages[index]
            if (message.role != MessageRole.USER || message.isSynthetic) return@mapNotNull null
            val reply = (index - 1 downTo 0).map { messages[it] }.firstOrNull { it.role == MessageRole.ASSISTANT && !it.isSynthetic }
                ?: return@mapNotNull null
            Triple(index, reply.at(), message.at())
        }
        if (windows.isEmpty()) return messages
        val calls = messages.filter { it.role == MessageRole.ASSISTANT }.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
        val found = runCatching { events(assistantId, calls, windows.minOf { it.second }) }.getOrElse { return messages }
        if (found.isEmpty()) return messages
        val notes = windows.mapNotNull { (index, from, to) ->
            found.filter { it.at > from && it.at <= to }.takeIf { it.isNotEmpty() }?.let { index to note(it) }
        }.toMap()
        if (notes.isEmpty()) return messages
        return buildList {
            messages.forEachIndexed { index, message ->
                notes[index]?.let { add(UIMessage.user(it).copy(isSynthetic = true)) }
                add(message)
            }
        }
    }

    private fun note(events: List<OutsideEvent>): String = buildString {
        append("<outside_this_chat>\n")
        append("Since your previous reply, outside this chat (from the app's records; $MY_RECORDS_TOOL has the full history):\n")
        events.forEach { append("- ").append(time(it.at)).append(' ').append(it.text).append('\n') }
        append("</outside_this_chat>")
    }
}
