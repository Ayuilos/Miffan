package me.ayuilos.miffan.utils

/**
 * Release notes as shown in the app. A release body (docs/releases/<version>.md) carries a Chinese
 * part, then an "Updates:" line and the English part; each part has "### " sections of "- " items.
 */
data class ReleaseNotes(
    val notice: String?,
    val sections: List<ReleaseNoteSection>,
) {
    val items: List<ReleaseNoteItem> get() = sections.flatMap { it.items }
    val isEmpty: Boolean get() = notice == null && sections.isEmpty()
}

data class ReleaseNoteSection(
    val title: String?,
    val items: List<ReleaseNoteItem>,
)

/** [title] is a short lead pulled from the item ("出门在外也能用电脑"); [body] is the rest, possibly empty. */
data class ReleaseNoteItem(
    val title: String?,
    val body: String,
    val details: List<String> = emptyList(),
)

private const val ENGLISH_MARKER = "Updates:"
private val PLACEHOLDERS = setOf(
    "本次无面向用户的改动。",
    "本次无面向用户的改动",
    "No user-facing changes in this release.",
    "No user-facing changes in this release",
)
private val BOLD_LEAD = Regex("""^\*\*(.+?)\*\*\s*[：:—-]?\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Picks the part for [chinese] and parses it; a body without the marker is used whole. */
fun parseReleaseNotes(body: String, chinese: Boolean): ReleaseNotes {
    val lines = body.replace("\r\n", "\n").lines()
    val marker = lines.indexOfFirst { it.trim() == ENGLISH_MARKER }
    val part = when {
        marker < 0 -> lines
        chinese -> lines.subList(0, marker)
        else -> lines.subList(marker + 1, lines.size)
    }
    return parsePart(part)
}

private fun parsePart(lines: List<String>): ReleaseNotes {
    val notice = mutableListOf<String>()
    val sections = mutableListOf<ReleaseNoteSection>()
    var sectionTitle: String? = null
    val items = mutableListOf<MutableList<String>>() // [text, detail, detail…]

    fun flush() {
        val parsed = items.mapNotNull { raw ->
            val text = raw.first().trim()
            if (text in PLACEHOLDERS) null else splitItem(text, raw.drop(1))
        }
        if (parsed.isNotEmpty()) sections += ReleaseNoteSection(sectionTitle, parsed)
        items.clear()
    }

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                flush()
                sectionTitle = trimmed.removePrefix("### ").trim().takeIf { it.isNotEmpty() }
            }
            trimmed.startsWith("#") -> Unit
            trimmed.startsWith(">") -> trimmed.removePrefix(">").trim().takeIf { it.isNotEmpty() }?.let(notice::add)
            line.startsWith("- ") || line.startsWith("* ") -> items += mutableListOf(line.substring(2))
            (trimmed.startsWith("- ") || trimmed.startsWith("* ")) && items.isNotEmpty() ->
                items.last() += trimmed.substring(2).trim()
            trimmed.isNotEmpty() && items.isNotEmpty() && line.first().isWhitespace() -> {
                // A wrapped continuation belongs to the last line of the current item.
                val current = items.last()
                current[current.lastIndex] = current.last().trimEnd() + " " + trimmed
            }
            else -> Unit
        }
    }
    flush()
    return ReleaseNotes(notice.joinToString(" ").takeIf { it.isNotEmpty() }, sections)
}

internal fun splitItem(text: String, details: List<String> = emptyList()): ReleaseNoteItem {
    BOLD_LEAD.matchEntire(text)?.let { match ->
        return ReleaseNoteItem(match.groupValues[1].trim(), match.groupValues[2].trim(), details)
    }
    // A short lead before a colon or the first full stop reads as a headline.
    val colon = text.indexOfAny(charArrayOf('：', ':'))
    // "电脑详情新增「出门在外」：" introduces its sub-items; the whole line is the headline.
    if (colon == text.length - 1 && details.isNotEmpty()) return ReleaseNoteItem(text.dropLast(1).trim(), "", details)
    if (colon in 2..28 && colon < text.length - 1) {
        return ReleaseNoteItem(text.substring(0, colon).trim(), text.substring(colon + 1).trim(), details)
    }
    val stop = text.indexOf('。')
    if (stop in 2..30 && stop < text.length - 1) {
        return ReleaseNoteItem(text.substring(0, stop).trim(), text.substring(stop + 1).trim(), details)
    }
    val englishStop = text.indexOf(". ")
    if (englishStop in 2..70) {
        return ReleaseNoteItem(text.substring(0, englishStop).trim(), text.substring(englishStop + 2).trim(), details)
    }
    return ReleaseNoteItem(null, text, details)
}
