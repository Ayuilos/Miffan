package me.ayuilos.miffan.data.ai.transformers

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.partner.partnerMentionHints
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Snapshot original nodes once per turn; append only to the provider's request copies. */
class PartnerMentionTransformer(conversation: Conversation, settings: Settings) : InputMessageTransformer {
    private val hints = partnerMentionHints(conversation, settings)

    internal fun apply(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        val hint = hints[message.id]?.takeIf { message.role == MessageRole.USER }
        if (hint == null) message else message.copy(parts = message.parts + UIMessagePart.Text(hint))
    }

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        apply(messages)
}
