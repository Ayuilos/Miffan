package me.ayuilos.miffan.ui.components.ai

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Tick02
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.pages.extensions.workspace.toShellStatusLabel
import me.ayuilos.miffan.ui.pages.extensions.workspace.remoteWorkspaceStatusLabel
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

@Composable
internal fun WorkspaceSelectSheet(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
    var query by remember { mutableStateOf("") }
    val workspaceRepository: WorkspaceRepository = koinInject()
    val hosts by workspaceRepository.listHostsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val hostStates by workspaceRepository.remoteHostStates.collectAsStateWithLifecycle()
    val workspaceStates by workspaceRepository.remoteWorkspaceStates.collectAsStateWithLifecycle()
    val visibleWorkspaces = workspaces.filter { workspace ->
        workspaceMatchesSelectionQuery(workspace, hosts.find { it.id == workspace.remoteHostId }, query)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_select),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.workspace_search_workspaces)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 不绑定
                WorkspaceSelectRow(
                    title = stringResource(R.string.workspace_no_binding),
                    rowTag = "workspace-select-none",
                    selected = assistant.workspaceId == null,
                    onClick = { onSelect(null) },
                )
                listOf(false, true).forEach { isRemote ->
                    val group = visibleWorkspaces.filter { it.isRemote == isRemote }
                    if (group.isNotEmpty()) Text(
                        if (isRemote) workspaceStrings.getString(R.string.workspace_filter_remote) else workspaceStrings.getString(R.string.workspace_filter_local),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 16.dp),
                    )
                    group.forEach { workspace ->
                        val host = hosts.find { it.id == workspace.remoteHostId }
                        WorkspaceSelectRow(
                            title = workspace.name,
                            rowTag = "workspace-select-${workspace.id}",
                            isRemote = isRemote,
                            statusLines = if (isRemote) listOf(
                                workspaceStrings.getString(R.string.workspace_remote_server_name, host?.name ?: workspaceStrings.getString(R.string.workspace_host_unavailable)),
                                when {
                                    host == null -> workspaceStrings.getString(R.string.workspace_host_config_unavailable)
                                    host.trustedHostKeySha256 == null -> workspaceStrings.getString(R.string.workspace_host_fingerprint_pending)
                                    else -> remoteWorkspaceStatusLabel(workspaceStrings,
                                        workspace,
                                        workspace.remoteHostId?.let(hostStates::get),
                                        workspaceStates[workspace.id],
                                    )
                                },
                            ) else listOf(workspaceStrings.getString(R.string.workspace_local_device), workspace.shellStatus.toShellStatusLabel()),
                            selected = workspace.id == assistant.workspaceId?.toString(),
                            onClick = { onSelect(workspace.id) },
                        )
                    }
                }
                if (visibleWorkspaces.isEmpty()) {
                    Text(
                        if (query.isBlank()) workspaceStrings.getString(R.string.workspace_empty_picker) else workspaceStrings.getString(R.string.workspace_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Icon(HugeIcons.Codesandbox, contentDescription = null)
                Text(stringResource(R.string.workspace_manage), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
internal fun WorkspaceSelectRow(
    title: String,
    rowTag: String,
    selected: Boolean,
    onClick: () -> Unit,
    isRemote: Boolean? = null,
    statusLines: List<String> = emptyList(),
) {
    val workspaceStrings = LocalResources.current
    ListItem(
        leadingContent = {
            Icon(
                if (isRemote == null) HugeIcons.Codesandbox else workspaceKindIcon(isRemote),
                contentDescription = isRemote?.let { workspaceKindLabel(workspaceStrings, it) },
            )
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = statusLines.takeIf { it.isNotEmpty() }?.let { lines ->
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            }
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .testTag(rowTag)
            .clickable { onClick() },
    )
}

internal fun workspaceMatchesSelectionQuery(
    workspace: WorkspaceEntity,
    host: RemoteHostEntity?,
    query: String,
): Boolean {
    val normalized = query.trim()
    if (normalized.isEmpty()) return true
    return listOfNotNull(
        workspace.name,
        workspace.remotePath,
        host?.name,
        host?.host,
        host?.username,
    ).any { it.contains(normalized, ignoreCase = true) }
}
