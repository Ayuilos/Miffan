package me.ayuilos.miffan.ui.im.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import kotlin.math.roundToInt
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.partner.PartnerMention
import me.ayuilos.miffan.data.partner.findPartnerMentions
import me.ayuilos.miffan.data.partner.partnerMentionNames
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.GlassShadow
import me.ayuilos.miffan.ui.components.ui.glass

/** An `@query` being typed: [start] is the index of the `@`, the query runs up to the cursor. */
internal data class MentionQuery(val start: Int, val query: String)

/** The `@query` right before the cursor, or null. An `@` inside a word (an email) does not count. */
internal fun TextFieldValue.mentionQuery(): MentionQuery? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    val start = text.lastIndexOf('@', cursor - 1)
    if (start < 0) return null
    if (start > 0 && text[start - 1].isAsciiWordChar()) return null
    val query = text.substring(start + 1, cursor)
    if (query.length > MAX_QUERY || query.any(Char::isWhitespace)) return null
    return MentionQuery(start, query)
}

private const val MAX_QUERY = 24

private fun Char.isAsciiWordChar() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '.'

/** A partner as the owner sees and @s it: a blank name shows as [blankName], as everywhere in the UI. */
internal data class MentionCandidate(val partner: Assistant, val name: String)

/** Partners whose name contains [query], names starting with it first. A finished name is not offered again. */
internal fun mentionCandidates(partners: List<Assistant>, query: String, blankName: String): List<MentionCandidate> {
    val named = partners.map { MentionCandidate(it, it.name.ifBlank { blankName }) }.filter { it.name != query }
    if (query.isEmpty()) return named
    return named.filter { it.name.contains(query, ignoreCase = true) }
        .sortedBy { if (it.name.startsWith(query, ignoreCase = true)) 0 else 1 }
}

/** Replaces the typed `@query` with `@name ` and puts the cursor after it. */
internal fun TextFieldValue.insertMention(mention: MentionQuery, name: String): TextFieldValue {
    val inserted = "@$name "
    val end = selection.start
    val rest = text.substring(end).let { if (it.startsWith(" ")) it.drop(1) else it }
    val next = text.substring(0, mention.start) + inserted + rest
    return TextFieldValue(next, TextRange(mention.start + inserted.length))
}

/**
 * `@name` tokens for [partners] as in the input: the `@` becomes the partner's avatar (inline content keyed
 * by [mentionAvatarKey]) and the name takes [style]. Copying still yields `@name`.
 */
internal fun mentionText(text: String, partners: List<Assistant>, style: SpanStyle): AnnotatedString {
    val mentions = findPartnerMentions(text, partnerMentionNames(partners))
    if (mentions.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        mentions.forEach { mention ->
            append(text.substring(at, mention.range.first))
            appendInlineContent(mentionAvatarKey(mention.id), "@")
            withStyle(style) { append(text.substring(mention.range.first + 1, mention.end)) }
            at = mention.end
        }
        append(text.substring(at))
    }
}

internal fun mentionAvatarKey(id: kotlin.uuid.Uuid) = "mention:$id"

/** Inline avatars for [mentionText], a little larger than the text and spaced from the name. */
internal fun mentionAvatars(partners: List<Assistant>): Map<String, InlineTextContent> = partners.associate { partner ->
    mentionAvatarKey(partner.id) to InlineTextContent(Placeholder(1.5.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            AssistantAvatar(partner.name, partner.avatar, Modifier.fillMaxHeight().aspectRatio(1f))
        }
    }
}

internal fun hasPartnerMention(text: String, partners: List<Assistant>) = '@' in text &&
    findPartnerMentions(text, partnerMentionNames(partners)).isNotEmpty()

/** The `@name` tokens of [partners] in [text]; each shows as one chip and edits as one unit. */
internal fun mentionChips(text: String, partners: List<Assistant>): List<PartnerMention> =
    if ('@' !in text || partners.isEmpty()) emptyList() else findPartnerMentions(text, partnerMentionNames(partners))

private val PartnerMention.end get() = range.last + 1

/**
 * Applies the field's edit [next] to [previous] so chips stay whole: an edit that removes part of a chip
 * (backspace at its end, a partial selection) removes all of it, and the cursor never rests inside one.
 */
internal fun keepChipsWhole(previous: TextFieldValue, next: TextFieldValue, partners: List<Assistant>): TextFieldValue {
    val chips = mentionChips(previous.text, partners)
    if (chips.isEmpty()) return next
    val old = previous.text
    val new = next.text
    if (old != new) {
        // The changed stretch: old[start, oldEnd) became new[start, newEnd).
        val start = old.commonPrefixWith(new).length
        val tail = old.substring(start).commonSuffixWith(new.substring(start)).length
        val oldEnd = old.length - tail
        val newEnd = new.length - tail
        if (oldEnd > start) {
            // The space inserted after a chip is invisible beside it, so backspacing it takes the chip too.
            val spaceAfterChip = newEnd == start && oldEnd == start + 1 && old[start] == ' '
            val cut = chips.filter { it.range.first < oldEnd && it.end > start || spaceAfterChip && it.end == start }
            if (cut.isNotEmpty()) {
                val from = minOf(start, cut.minOf { it.range.first })
                val to = maxOf(oldEnd, cut.maxOf { it.end })
                val inserted = new.substring(start, newEnd)
                val text = old.substring(0, from) + inserted + old.substring(to)
                return TextFieldValue(text, TextRange(from + inserted.length))
            }
        }
        return next.outsideChips(mentionChips(new, partners), previous.selection)
    }
    return next.outsideChips(chips, previous.selection)
}

/** Moves a selection edge that falls inside a chip to the chip's edge, toward where the cursor was heading. */
private fun TextFieldValue.outsideChips(chips: List<PartnerMention>, before: TextRange): TextFieldValue {
    fun snap(offset: Int, forward: Boolean): Int {
        val chip = chips.firstOrNull { offset > it.range.first && offset < it.end } ?: return offset
        return if (forward) chip.end else chip.range.first
    }
    val sel = selection
    val moved = if (sel.collapsed) {
        val at = snap(sel.start, forward = sel.start >= before.start)
        TextRange(at)
    } else TextRange(snap(sel.start, forward = sel.start > sel.end), snap(sel.end, forward = sel.end >= sel.start))
    return if (moved == sel) this else copy(selection = moved)
}

/**
 * Shows each chip's `@` as an empty slot for the avatar and its name in the chip color; the text itself
 * stays `@name`, so offsets map one to one.
 */
internal class MentionChipStyle(private val partners: List<Assistant>, private val nameColor: Color, private val slot: TextUnit) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val chips = mentionChips(text.text, partners)
        if (chips.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            chips.forEach { chip ->
                addStyle(SpanStyle(color = Color.Transparent, letterSpacing = slot), chip.range.first, chip.range.first + 1)
                addStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium), chip.range.first + 1, chip.end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is MentionChipStyle && other.partners == partners && other.nameColor == nameColor && other.slot == slot
    override fun hashCode() = (partners.hashCode() * 31 + nameColor.hashCode()) * 31 + slot.hashCode()
}

/**
 * Draws the chips under the input's text: a rounded background per line a chip covers, and the partner's
 * avatar in the slot left by its `@`. Place it in the field's decoration box, sized to the text.
 */
@Composable
internal fun BoxScope.MentionChipLayer(text: String, partners: List<Assistant>, layout: TextLayoutResult?, color: Color) {
    if (layout == null || layout.layoutInput.text.text != text) return
    val chips = mentionChips(text, partners)
    if (chips.isEmpty()) return
    val density = LocalDensity.current
    val inset = with(density) { 1.dp.toPx() }
    val pad = with(density) { 6.dp.toPx() }
    Canvas(Modifier.matchParentSize()) {
        chips.forEach { chip ->
            chip.range.groupBy { layout.getLineForOffset(it) }.values.forEach { offsets ->
                val boxes = offsets.map { layout.getBoundingBox(it) }
                val top = layout.getLineTop(layout.getLineForOffset(offsets.first())) + inset
                val bottom = layout.getLineBottom(layout.getLineForOffset(offsets.first())) - inset
                val left = boxes.minOf { it.left }
                val right = boxes.maxOf { it.right } + pad
                val radius = (bottom - top) / 2
                drawRoundRect(color, Offset(left, top), Size(right - left, bottom - top), CornerRadius(radius))
            }
        }
    }
    chips.forEach { chip ->
        val partner = partners.firstOrNull { it.id == chip.id } ?: return@forEach
        val box = layout.getBoundingBox(chip.range.first)
        val line = layout.getLineForOffset(chip.range.first)
        // The avatar fills the chip's height at its rounded left end, like a contact chip.
        val top = layout.getLineTop(line) + inset
        val size = minOf(layout.getLineBottom(line) - inset - top, box.width)
        AssistantAvatar(partner.name, partner.avatar, Modifier
            .offset { IntOffset(box.left.roundToInt(), top.roundToInt()) }
            .size(with(density) { size.toDp() }))
    }
}

/**
 * Partners to @, floating above the input as wide as it. It never takes focus, so the keyboard stays up
 * and typing keeps narrowing the list.
 */
@Composable
internal fun ThreadMentionPopup(candidates: List<MentionCandidate>, width: Int, hazeState: HazeState, onPick: (MentionCandidate) -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = candidates.isNotEmpty()
    if (!shown.currentState && !shown.targetState) return
    // Keep the last rows while fading out, so the card does not collapse first.
    val last = remember { arrayOf(candidates) }
    if (candidates.isNotEmpty()) last[0] = candidates
    val rows = last[0]
    val density = LocalDensity.current
    val room = with(density) { SHADOW_ROOM.roundToPx() }
    // The window ends at the input's top edge (the gap is its bottom room), so it never covers the input;
    // the shadow below the card is cut there, where the input begins anyway.
    val position = remember(room) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(anchorBounds.left - room, anchorBounds.top - popupContentSize.height)
        }
    }
    val shape = RoundedCornerShape(24.dp)
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        AnimatedVisibility(shown, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.padding(start = SHADOW_ROOM, top = SHADOW_ROOM, end = SHADOW_ROOM, bottom = 8.dp)) {
                GlassShadow(shape, Modifier.matchParentSize())
                LazyColumn(Modifier.width(with(density) { width.toDp() }).heightIn(max = 248.dp).glass(hazeState, shape),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(rows, key = { it.partner.id.toString() }) { candidate ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(candidate) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistantAvatar(candidate.partner.name, candidate.partner.avatar, Modifier.size(32.dp))
                            Text(candidate.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private val SHADOW_ROOM = 48.dp
