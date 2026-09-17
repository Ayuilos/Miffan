package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01

@Composable
internal fun WorkspaceIntroduction(onDismiss: () -> Unit, onOpenWorkspaces: () -> Unit) {
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("3.4 新功能", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("远程工作空间", modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.headlineMedium)
                    Text("让 AI 在你的服务器上工作。", modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        WorkspaceIntroductionFeature(HugeIcons.ComputerTerminal01, "连接你的服务器",
                            "通过 SSH 管理多台主机，也支持已接入的 Tailscale 私网。")
                        WorkspaceIntroductionFeature(HugeIcons.Folder01, "文件与终端，一处完成",
                            "浏览和编辑文件，在远程终端执行命令。")
                        WorkspaceIntroductionFeature(HugeIcons.Bash, "把任务交给 AI",
                            "将工作空间绑定到助手，让 AI 在指定目录中读写文件、执行命令。执行权限由你管理。")
                    }
                    Button(onClick = onOpenWorkspaces, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
                        Text("打开工作空间")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("稍后再说") }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceIntroductionFeature(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
