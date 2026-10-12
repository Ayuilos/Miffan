package me.ayuilos.miffan.ui.im.thread

import androidx.compose.animation.AnimatedVisibility
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

/** `@name` tokens for [partners], styled with [style]. */
internal fun mentionText(text: String, partners: List<Assistant>, style: SpanStyle): AnnotatedString {
    val mentions = findPartnerMentions(text, partnerMentionNames(partners))
    if (mentions.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        mentions.forEach { addStyle(style, it.range.first, it.range.last + 1) }
    }
}

internal fun hasPartnerMention(text: String, partners: List<Assistant>) = '@' in text &&
    findPartnerMentions(text, partnerMentionNames(partners)).isNotEmpty()

/** Colors `@name` tokens in the input as the owner types; the text itself is unchanged. */
internal class MentionHighlight(private val partners: List<Assistant>, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(mentionText(text.text, partners, SpanStyle(color = color, fontWeight = FontWeight.Medium)), OffsetMapping.Identity)

    override fun equals(other: Any?) = other is MentionHighlight && other.partners == partners && other.color == color
    override fun hashCode() = partners.hashCode() * 31 + color.hashCode()
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
