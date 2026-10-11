package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant

/** An informational event plus the partner's words; no owner trigger and no generation. */
internal fun Conversation.withPartnerOwnerNote(origin: MessageOrigin.Event, text: String): Conversation =
    appendMessage(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(origin.summary))), origin = origin)
        .appendMessage(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))))
        .copy(updateAt = Instant.now())
