package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.isImMode
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/** Resolve the original text, before templates or other request-only transformations. */
internal fun mentionedOtherPartners(text: String, assistantId: Uuid, settings: Settings): List<Uuid> =
    findPartnerMentions(text, partnerMentionNames(settings.assistants))
        // Match self too, so a self-name cannot accidentally match another partner's shorter name.
        .map { it.id }.filter { it != assistantId }.distinct()

/** Only the owner message starting this IM turn can replace the first-contact approval. */
internal fun Conversation.ownerMentionedPartnerIds(settings: Settings): List<Uuid> {
    if (!settings.isImMode || chatId != null) return emptyList()
    val trigger = ownerTrigger() ?: return emptyList()
    val message = getMessageNodeByMessageId(trigger.messageId)?.message ?: return emptyList()
    return mentionedOtherPartners(message.toText(), assistantId, settings)
}

/** No clock, random ids or mutable grant state: historical hints are deterministic. */
internal fun partnerMentionHints(conversation: Conversation, settings: Settings): Map<Uuid, String> {
    if (!settings.isImMode || conversation.chatId != null) return emptyMap()
    val assistants = settings.assistants.associateBy { it.id }
    return conversation.currentMessageNodes.mapNotNull { node ->
        if (node.origin != null || node.role != MessageRole.USER) return@mapNotNull null
        val ids = mentionedOtherPartners(node.message.toText(), conversation.assistantId, settings)
        if (ids.isEmpty()) return@mapNotNull null
        node.message.id to buildString {
            appendLine("<partner_mentions>")
            appendLine("主人在这条消息里 @ 了 Miffan 里的其他伙伴（不是普通人名）：")
            ids.forEach { id ->
                val assistant = assistants.getValue(id)
                val introduction = assistant.systemPrompt.lineSequence().firstOrNull().orEmpty().take(160)
                appendLine("- ${partnerDisplayName(assistant)}（联系人 id: $id）：$introduction")
            }
            appendLine("需要联系时直接调用 send_message，to 填上面的 id，不必先调用 list_contacts。主人 @ 了谁，就表示已经同意你联系谁。")
            append("</partner_mentions>")
        }
    }.toMap()
}
