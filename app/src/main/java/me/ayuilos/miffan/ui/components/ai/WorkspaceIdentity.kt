package me.ayuilos.miffan.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01

internal fun workspaceKindLabel(isRemote: Boolean): String = if (isRemote) "远程服务器" else "本地设备"

internal fun workspaceKindIcon(isRemote: Boolean): ImageVector =
    if (isRemote) HugeIcons.ComputerTerminal01 else HugeIcons.Folder01

@Composable
internal fun WorkspaceKindLabel(isRemote: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(workspaceKindIcon(isRemote), contentDescription = null)
        Text(workspaceKindLabel(isRemote), style = MaterialTheme.typography.labelSmall)
    }
}
