package me.ayuilos.miffan.ui.components.ai

import androidx.compose.ui.platform.LocalResources
import android.content.res.Resources
import me.ayuilos.miffan.R
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

internal fun workspaceKindLabel(workspaceStrings: Resources, isRemote: Boolean): String = if (isRemote) workspaceStrings.getString(R.string.workspace_remote_server) else workspaceStrings.getString(R.string.workspace_local_device)

internal fun workspaceKindIcon(isRemote: Boolean): ImageVector =
    if (isRemote) HugeIcons.ComputerTerminal01 else HugeIcons.Folder01

@Composable
internal fun WorkspaceKindLabel(isRemote: Boolean, modifier: Modifier = Modifier) {
    val workspaceStrings = LocalResources.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(workspaceKindIcon(isRemote), contentDescription = null)
        Text(workspaceKindLabel(workspaceStrings, isRemote), style = MaterialTheme.typography.labelSmall)
    }
}
