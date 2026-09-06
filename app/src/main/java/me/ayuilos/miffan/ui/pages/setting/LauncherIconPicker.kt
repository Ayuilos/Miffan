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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.utils.LauncherIcon
import me.ayuilos.miffan.utils.LauncherIconManager

@Composable
fun LauncherIconPicker() {
    val context = LocalContext.current
    val manager = remember(context) { LauncherIconManager(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var selected by remember(manager) { mutableStateOf<LauncherIcon?>(null) }
    var switching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var hasError by remember { mutableStateOf(false) }

    DisposableEffect(manager, lifecycleOwner) {
        fun refresh() {
            try {
                selected = manager.selectedIcon()
                if (selected == null) {
                    message = "桌面图标状态异常，请重新选择一个图标。"
                    hasError = true
                }
            } catch (_: Exception) {
                message = "无法读取桌面图标，请稍后重试。"
                hasError = true
            }
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "桌面图标",
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Card {
            Column(Modifier.selectableGroup()) {
                LauncherIcon.entries.forEach { icon ->
                    val preview = remember(context, icon) {
                        ContextCompat.getDrawable(context, icon.preview)
                            ?.toBitmap(width = 192, height = 192)?.asImageBitmap()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == icon,
                                enabled = !switching,
                                role = Role.RadioButton,
                                onClick = {
                                    switching = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { manager.select(icon) }
                                            selected = manager.selectedIcon()
                                            message = "已切换为${icon.label}，桌面和分享列表可能需要片刻刷新。"
                                            hasError = false
                                        } catch (_: Exception) {
                                            selected = runCatching { manager.selectedIcon() }.getOrNull()
                                            message = "图标切换失败，请重试。"
                                            hasError = true
                                        } finally {
                                            switching = false
                                        }
                                    }
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (preview != null) {
                            Image(preview, contentDescription = null, modifier = Modifier.size(48.dp))
                        }
                        Text(icon.label, modifier = Modifier.weight(1f))
                        RadioButton(selected = selected == icon, onClick = null, enabled = !switching)
                    }
                }
            }
        }
        Text(
            text = message ?: "桌面、分享和链接入口使用同一图标，也可以随时恢复原版饭碗。",
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (hasError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
