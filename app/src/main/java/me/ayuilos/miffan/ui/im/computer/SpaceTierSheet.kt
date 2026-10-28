package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.SpaceCapacity
import me.ayuilos.miffan.data.ai.computer.SpaceTier
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.InformationCircle

@Composable
internal fun SpaceTier.label(): String = stringResource(when (this) {
    SpaceTier.LIGHT -> R.string.im_space_tier_light
    SpaceTier.STANDARD -> R.string.im_space_tier_standard
    SpaceTier.PERFORMANCE -> R.string.im_space_tier_performance
})

/** "Standard · 2 cores · 4 GB" */
@Composable
internal fun SpaceTier.summary(): String =
    label() + " · " + stringResource(R.string.im_space_tier_spec, cpus, memoryMb / 1024)

@Composable
private fun SpaceTier.hint(): String = stringResource(when (this) {
    SpaceTier.LIGHT -> R.string.im_space_tier_light_hint
    SpaceTier.STANDARD -> R.string.im_space_tier_standard_hint
    SpaceTier.PERFORMANCE -> R.string.im_space_tier_performance_hint
})

/**
 * The three sizes, the recommended one marked. A size the computer cannot give (per [capacity], next to
 * [usedMemoryMb] already promised to other Spaces, for [count] new ones) stays visible but cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceTierSheet(
    current: SpaceTier,
    recommended: SpaceTier?,
    capacity: SpaceCapacity?,
    usedMemoryMb: Int,
    count: Int,
    note: String? = null,
    enabled: Boolean = true,
    onPick: (SpaceTier) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.im_space_tier_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.im_space_tier_sheet_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let { SetupNote(HugeIcons.InformationCircle, it) }
            SpaceTier.entries.forEach { tier ->
                val fits = capacity?.fits(tier, usedMemoryMb, count) ?: true
                val selectable = enabled && fits
                val selected = tier == current
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).alpha(if (fits) 1f else 0.5f)
                        .selectable(selected, enabled = selectable, role = Role.RadioButton) { onPick(tier) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tier.label(), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.im_space_tier_spec, tier.cpus, tier.memoryMb / 1024),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (tier == recommended) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(stringResource(R.string.im_space_tier_recommended), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Text(if (fits) tier.hint() else stringResource(R.string.im_space_tier_too_big),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = selected, onClick = null, enabled = selectable)
                    }
                }
            }
        }
    }
}
