package me.ayuilos.miffan.ui.components.ui

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
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
    val workspaceStrings = LocalResources.current
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text(stringResource(R.string.workspace_intro_badge), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.workspace_intro_title), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.workspace_intro_subtitle), modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        WorkspaceIntroductionFeature(HugeIcons.ComputerTerminal01, workspaceStrings.getString(R.string.workspace_intro_connect_title),
                            workspaceStrings.getString(R.string.workspace_intro_connect_description))
                        WorkspaceIntroductionFeature(HugeIcons.Folder01, workspaceStrings.getString(R.string.workspace_intro_files_title),
                            workspaceStrings.getString(R.string.workspace_intro_files_description))
                        WorkspaceIntroductionFeature(HugeIcons.Bash, workspaceStrings.getString(R.string.workspace_intro_ai_title),
                            workspaceStrings.getString(R.string.workspace_intro_ai_description))
                    }
                    Button(onClick = onOpenWorkspaces, modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
                        Text(stringResource(R.string.workspace_open_workspaces))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workspace_later)) }
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
