package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImPageBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNavController.current
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack ?: nav::popBackStack) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
    }, actions = actions)
}

@Composable
internal fun ImEmpty(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImSettingRow(title: String, onClick: () -> Unit, supporting: String? = null, destructive: Boolean = false) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Caption above an [ImCardGroup]. */
@Composable
internal fun ImSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The rows of one settings group on a single card; separate them with [ImCardDivider]. */
@Composable
internal fun ImCardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(content = content)
    }
}

/** Divider between [ImListRow]s, starting where their text does. */
@Composable
internal fun ImCardDivider(start: Dp = 68.dp) {
    HorizontalDivider(Modifier.padding(start = start), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** Colour family of an [ImListRow]'s icon tile, so groups of rows can be told apart at a glance. */
internal enum class ImIconTone { PRIMARY, SECONDARY, TERTIARY, NEUTRAL }

/**
 * One row of an [ImCardGroup]: an icon tile, a title with optional supporting text, and a trailing slot
 * that shows a chevron for rows that open something.
 */
@Composable
internal fun ImListRow(
    icon: ImageVector?,
    title: String,
    onClick: (() -> Unit)?,
    supporting: String? = null,
    tone: ImIconTone = ImIconTone.NEUTRAL,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = if (onClick != null) {
        { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ImIconTone.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        ImIconTone.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        ImIconTone.TERTIARY -> colors.tertiaryContainer to colors.onTertiaryContainer
        ImIconTone.NEUTRAL -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Surface(color = container, contentColor = content, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}
