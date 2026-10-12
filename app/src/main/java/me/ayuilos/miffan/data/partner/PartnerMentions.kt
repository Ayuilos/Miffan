package me.ayuilos.miffan.data.partner

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
