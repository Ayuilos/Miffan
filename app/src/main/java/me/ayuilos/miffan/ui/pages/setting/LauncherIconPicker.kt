package me.ayuilos.miffan.ui.pages.setting

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.utils.LauncherIcon
import me.ayuilos.miffan.utils.LauncherIconManager
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R

/** Which launcher icon is in use, and switching it; [message] says how the last switch went. */
@Stable
class LauncherIconState internal constructor(
    private val manager: LauncherIconManager,
    private val resources: Resources,
    private val scope: CoroutineScope,
) {
    var selected by mutableStateOf<LauncherIcon?>(null)
        private set
    var switching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var hasError by mutableStateOf(false)
        private set

    internal fun refresh() {
        try {
            selected = manager.selectedIcon()
            if (selected == null) {
                message = resources.getString(R.string.launcher_icon_state_invalid)
                hasError = true
            }
        } catch (_: Exception) {
            message = resources.getString(R.string.launcher_icon_read_failed)
            hasError = true
        }
    }

    fun select(icon: LauncherIcon) {
        switching = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { manager.select(icon) }
                selected = manager.selectedIcon()
                message = resources.getString(R.string.launcher_icon_switched, resources.getString(icon.label))
                hasError = false
            } catch (_: Exception) {
                selected = runCatching { manager.selectedIcon() }.getOrNull()
                message = resources.getString(R.string.launcher_icon_switch_failed)
                hasError = true
            } finally {
                switching = false
            }
        }
    }
}

/** Reads the icon in use now and again whenever the app comes back, since the launcher can change it too. */
@Composable
fun rememberLauncherIconState(): LauncherIconState {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context) { LauncherIconState(LauncherIconManager(context), resources, scope) }
    DisposableEffect(state, lifecycleOwner) {
        state.refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** The icon's picture at launcher size, or null if it cannot be drawn. */
@Composable
fun rememberLauncherIconPreview(icon: LauncherIcon): ImageBitmap? {
    val context = LocalContext.current
    return remember(context, icon) {
        ContextCompat.getDrawable(context, icon.preview)?.toBitmap(width = 192, height = 192)?.asImageBitmap()
    }
}

@Composable
fun LauncherIconPicker() {
    val state = rememberLauncherIconState()

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.launcher_icon_title),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Card {
            Column(Modifier.selectableGroup()) {
                LauncherIcon.choices.forEach { icon ->
                    val preview = rememberLauncherIconPreview(icon)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.selected == icon,
                                enabled = !state.switching,
                                role = Role.RadioButton,
                                onClick = { state.select(icon) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (preview != null) {
                            Image(preview, contentDescription = null, modifier = Modifier.size(48.dp))
                        }
                        Text(stringResource(icon.label), modifier = Modifier.weight(1f))
                        RadioButton(selected = state.selected == icon, onClick = null, enabled = !state.switching)
                    }
                }
            }
        }
        Text(
            text = state.message ?: stringResource(R.string.launcher_icon_hint),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.hasError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
