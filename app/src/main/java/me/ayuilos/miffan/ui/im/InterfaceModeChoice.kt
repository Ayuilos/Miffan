package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Settings03
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.withInterfaceMode

/**
 * Asks an upgraded installation once which shell to use. The choice cannot be dismissed, because
 * both shells must remain reachable afterwards from their own settings.
 */
@Composable
fun InterfaceModeChoiceHost(
    settings: Settings,
    store: SettingsStore,
    eligible: Boolean,
    onChosen: (Settings) -> Unit,
) {
    if (!eligible || !settings.interfaceMode.choicePending) return
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(InterfaceMode.IM) }
    var saving by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.im_mode_choice_title)) },
        text = {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.im_mode_choice_desc))
                ModeOption(
                    icon = HugeIcons.Message01,
                    title = stringResource(R.string.im_mode_easy),
                    description = stringResource(R.string.im_mode_easy_desc),
                    isNew = true,
                    selected = selected == InterfaceMode.IM,
                    onSelect = { selected = InterfaceMode.IM },
                )
                ModeOption(
                    icon = HugeIcons.Settings03,
                    title = stringResource(R.string.im_mode_professional),
                    description = stringResource(R.string.im_mode_professional_desc),
                    isNew = false,
                    selected = selected == InterfaceMode.PROFESSIONAL,
                    onSelect = { selected = InterfaceMode.PROFESSIONAL },
                )
                Text(
                    text = stringResource(R.string.im_mode_choice_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            store.update { it.withInterfaceMode(selected) }
                            onChosen(store.settingsFlow.value)
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.im_mode_choice_continue))
            }
        },
    )
}

@Composable
private fun ModeOption(
    icon: ImageVector,
    title: String,
    description: String,
    isNew: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (isNew) Badge { Text(stringResource(R.string.im_mode_new)) }
                }
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
