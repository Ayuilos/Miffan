package me.ayuilos.miffan.data.partner

import me.ayuilos.miffan.data.model.Assistant
import kotlin.uuid.Uuid

/** One `@name` in a text: [range] covers the `@` and the name. */
data class PartnerMention(val id: Uuid, val range: IntRange)

/**
 * Finds `@name` tokens for [names] (name to participant id), matching each `@` once and preferring
 * the longest name, so `@小B` is not read as `@小` when both exist. Blank names never match.
 */
fun findPartnerMentions(text: String, names: List<Pair<String, Uuid>>): List<PartnerMention> {
    val ordered = names.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
    if (ordered.isEmpty()) return emptyList()
    val mentions = mutableListOf<PartnerMention>()
    var index = 0
    while (index < text.length) {
        if (text[index] == '@') {
            val match = ordered.firstOrNull { text.startsWith(it.first, index + 1) }
            if (match != null) {
                mentions += PartnerMention(match.second, index..index + match.first.length)
                index += match.first.length
            }
        }
        index++
    }
    return mentions
}

/**
 * What the UI calls a partner with a blank name (`assistant_page_default_assistant`) in every app language.
 * Keep in sync with the string resources; PartnerMentionsTest checks it.
 */
val BLANK_PARTNER_NAMES = listOf("Default Assistant", "默认助手", "預設助手", "デフォルトアシスタント", "기본 어시스턴트", "Ассистент по умолчанию")

/** Stable data/tool name; blank UI names are recognized in every language by [partnerMentionNames]. */
fun partnerDisplayName(assistant: Assistant?): String =
    assistant?.name?.ifBlank { BLANK_PARTNER_NAMES[1] } ?: "已删除的伙伴"

/**
 * The names an owner can @ each partner by: its own name, or for a blank-named partner the name the UI shows,
 * in any language, so a mention survives switching the app language.
 */
fun partnerMentionNames(assistants: List<Assistant>): List<Pair<String, Uuid>> = assistants.flatMap { assistant ->
    if (assistant.name.isBlank()) BLANK_PARTNER_NAMES.map { it to assistant.id } else listOf(assistant.name to assistant.id)
}
