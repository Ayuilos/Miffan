package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Tick01

/*
 * The cards of easy mode share three pieces, so every request reads the same way:
 * - [ImActionCard]: anything the partner (or the first run) asks the owner to confirm, answer or look at.
 * - [ImOptionList]: choosing one or several things with care; a row only selects, a button settles it.
 * - [ImChoiceQuiz]: ready answers under a partner's message; tapping one sends it, typing instead retires it.
 */

/**
 * The frame of a card: a header (icon tile, title, optional action on the right), then [content].
 * Put values in [ImCardBlock]s and the answer in [ImCardButtons] or, once given, an [ImCardStatus].
 */
@Composable
internal fun ImActionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    /** A small line above the title, e.g. "Question 2 of 3". */
    overline: String? = null,
    leading: (@Composable () -> Unit)? = icon?.let { { ImCardIcon(it) } },
    trailing: (@Composable () -> Unit)? = null,
    /** Tighter spacing for cards laid over other content (the partner's screen). */
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            if (title != null || overline != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    overline?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    title?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/** The header's icon tile. */
@Composable
internal fun ImCardIcon(icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

/** The light block a card's values sit in. */
@Composable
internal fun ImCardBlock(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    val shape = MaterialTheme.shapes.medium
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, color = color, shape = shape) {
        Column(Modifier.fillMaxWidth(), content = content)
    } else Surface(modifier, color = color, shape = shape) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** Divider between the rows of an [ImCardBlock]. */
@Composable
internal fun ImCardBlockDivider(start: Dp = 0.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.surfaceContainerHigh)
}

/** A card's answers side by side, equally wide; a single one spans the card. */
@Composable
internal fun ImCardButtons(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun RowScope.ImCardButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    Button(onClick, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled,
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RowScope.ImCardOutlinedButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) {
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Takes the buttons' place once the owner answered, at the same height so the card does not jump. */
@Composable
internal fun ImCardStatus(text: String, working: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(HugeIcons.Tick01, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text)
        }
    }
}

/** One row of an [ImOptionList]. */
internal class ImOption<K>(
    val key: K,
    val title: String,
    val supporting: String? = null,
    /** A short tag after the title ("Free"). */
    val tag: String? = null,
    val leading: (@Composable () -> Unit)? = null,
)

/**
 * Things to choose from, as full-width rows in an [ImCardBlock]: radio buttons for one, checkboxes for
 * several. Selecting is all a row does; the card's button settles the choice. With [maxHeight] the
 * rows scroll inside the block (long lists, usually under a search field).
 */
@Composable
internal fun <K> ImOptionList(
    options: List<ImOption<K>>,
    selected: Set<K>,
    onSelect: (K) -> Unit,
    multiple: Boolean = false,
    enabled: Boolean = true,
    maxHeight: Dp? = null,
) {
    if (options.isEmpty()) return
    ImCardBlock {
        if (maxHeight != null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            itemsIndexed(options, key = { _, option -> option.key.toString() }) { index, option ->
                if (index > 0) ImCardBlockDivider()
                ImOptionRow(option, option.key in selected, multiple, enabled) { onSelect(option.key) }
            }
        } else options.forEachIndexed { index, option ->
            if (index > 0) ImCardBlockDivider()
            ImOptionRow(option, option.key in selected, multiple, enabled) { onSelect(option.key) }
        }
    }
}

@Composable
private fun <K> ImOptionRow(option: ImOption<K>, on: Boolean, multiple: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val select = if (multiple) Modifier.toggleable(on, enabled = enabled, role = Role.Checkbox) { onClick() }
        else Modifier.selectable(on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Row(Modifier.fillMaxWidth()
        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else androidx.compose.ui.graphics.Color.Transparent)
        .then(select).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (multiple) Checkbox(on, onCheckedChange = null, enabled = enabled) else RadioButton(on, onClick = null, enabled = enabled)
        option.leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(option.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                option.tag?.let { ImTag(it) }
            }
            option.supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A short label next to a name ("Free", "One tap"). */
@Composable
internal fun ImTag(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** How a [ImChoiceQuiz] was settled: one of its options, or the owner's own words instead. */
internal sealed interface QuizAnswer {
    data class Picked(val index: Int) : QuizAnswer
    data object Replied : QuizAnswer
}

/**
 * Ready answers hanging under a partner's message, like a multiple-choice question: tapping one sends it
 * at once. Once answered, by an option or by typing something else, it folds into one line that opens to
 * show the options again, no longer tappable.
 */
@Composable
internal fun ImChoiceQuiz(
    title: String?,
    options: List<String>,
    answer: QuizAnswer?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Says the owner may type instead; off where there is nothing to type into. */
    typeHint: Boolean = true,
) {
    if (options.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape) {
        Column(Modifier.fillMaxWidth().padding(if (answer == null) 12.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (answer == null) {
                if (title != null || typeHint) Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(HugeIcons.LeftToRightListBullet, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (typeHint) Text(stringResource(R.string.im_quiz_or_type), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                options.forEachIndexed { index, option ->
                    QuizOption(index, option, chosen = false, onClick = if (enabled) ({ onPick(index) }) else null)
                }
            } else {
                ImFoldedLine(when (answer) {
                    is QuizAnswer.Picked -> stringResource(R.string.im_quiz_picked, quizLetter(answer.index))
                    QuizAnswer.Replied -> stringResource(R.string.im_quiz_replied)
                }, expanded) { expanded = !expanded }
                if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEachIndexed { index, option ->
                        QuizOption(index, option, chosen = answer == QuizAnswer.Picked(index), onClick = null)
                    }
                }
            }
        }
    }
}

/** A settled question folded into one line; tapping it shows what was asked again, read-only. */
@Composable
internal fun ImFoldedLine(summary: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onToggle)
        .heightIn(min = 40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(HugeIcons.LeftToRightListBullet, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (expanded) R.string.im_quiz_hide else R.string.im_quiz_view),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun quizLetter(index: Int): String = ('A' + index).toString()

@Composable
private fun QuizOption(index: Int, text: String, chosen: Boolean, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val color = if (chosen) colors.primaryContainer else colors.surfaceContainerLowest
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = if (chosen) colors.primary else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (chosen) colors.onPrimary else colors.primary,
                border = if (chosen) null else BorderStroke(1.5.dp, colors.outlineVariant), modifier = Modifier.size(26.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(quizLetter(index), style = MaterialTheme.typography.labelLarge) }
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
    // Read-only options (an answered quiz) stay readable but look settled.
    if (onClick != null) Surface(onClick = onClick, color = color, shape = MaterialTheme.shapes.medium) { row() }
    else Surface(Modifier.alpha(if (chosen) 1f else .7f), color = color, shape = MaterialTheme.shapes.medium) { row() }
}
