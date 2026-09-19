package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.utils.workspaceErrorMessage
import me.ayuilos.miffan.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.KeyEvent
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import org.koin.compose.koinInject

@Composable
fun RemoteWorkspaceTerminalPage(
    workspace: WorkspaceEntity,
    host: RemoteHostEntity?,
    onBack: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
    val repository: WorkspaceRepository = koinInject()
    val context = LocalContext.current
    val terminalView = remember(workspace.id) { RemoteTerminalView(context) }
    val identity = remember(workspace.id, host?.id) { host?.let {
        RemoteTerminalIdentity(
            hostId = it.id,
            revision = it.connectionRevision,
            hostName = it.name,
            endpoint = "${it.username}@${it.host}:${it.port}",
            remoteRoot = workspace.remotePath.orEmpty(),
        )
    } }
    val targetChanged = identity != null && (host?.id != identity.hostId ||
        host.connectionRevision != identity.revision || workspace.remotePath != identity.remoteRoot)
    var session by remember(workspace.id) { mutableStateOf<RemoteTerminalRegistry.Session?>(null) }
    val observedState by (session?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow<RemoteTerminalUiState>(
        RemoteTerminalUiState.Connecting,
    ) }).collectAsState()
    val state = when {
        targetChanged -> RemoteTerminalUiState.TargetChanged
        identity == null -> RemoteTerminalUiState.HostMissing
        else -> observedState
    }
    var showCloseConfirm by remember(workspace.id) { mutableStateOf(false) }

    BackHandler { showCloseConfirm = true }
    LaunchedEffect(workspace.id, identity, targetChanged) {
        if (targetChanged) {
            RemoteTerminalRegistry.close(workspace.id)
            session = null
            return@LaunchedEffect
        }
        if (identity == null) {
            session = null
            return@LaunchedEffect
        }
        session = RemoteTerminalRegistry.getOrOpen(workspace.id, identity, repository,
            terminalView.columns, terminalView.rows)
    }
    DisposableEffect(terminalView, session) {
        val active = session
        if (active != null) {
            terminalView.screen = active.screen
            terminalView.sendBytes = active::write
            terminalView.onSizeInCellsChanged = active::resize
            active.resize(terminalView.columns, terminalView.rows)
        }
        onDispose {
            terminalView.sendBytes = {}
            terminalView.onSizeInCellsChanged = { _, _ -> }
            terminalView.detachScreen()
        }
    }
    MiffanTheme(colorMode = ColorMode.DARK) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                identity?.endpoint ?: workspaceStrings.getString(R.string.workspace_host_config_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = { BackButton(onClick = { showCloseConfirm = true }) },
                )
            },
        ) { padding ->
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                color = Color.Black,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { terminalView },
                            modifier = Modifier.fillMaxSize(),
                        )
                        when (val current = state) {
                            RemoteTerminalUiState.Connecting -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text(stringResource(R.string.workspace_connecting_host, identity?.hostName ?: workspaceStrings.getString(R.string.workspace_remote_host)))
                                }
                            }
                            RemoteTerminalUiState.Connected -> Unit
                            else -> {
                                Column(
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = when (current) {
                                            is RemoteTerminalUiState.Disconnected ->
                                                if (current.exitStatus >= 0) workspaceStrings.getString(R.string.workspace_connection_ended_exit, current.exitStatus) else workspaceStrings.getString(R.string.workspace_disconnected)
                                            is RemoteTerminalUiState.Failed -> current.error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_terminal_connection_failed)
                                            RemoteTerminalUiState.TargetChanged -> workspaceStrings.getString(R.string.workspace_terminal_target_changed)
                                            RemoteTerminalUiState.HostMissing -> workspaceStrings.getString(R.string.workspace_remote_host_config_unavailable)
                                            else -> workspaceStrings.getString(R.string.workspace_disconnected)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (current is RemoteTerminalUiState.Disconnected || current is RemoteTerminalUiState.Failed) {
                                        TextButton(onClick = { identity?.let { session = RemoteTerminalRegistry.restart(workspace.id, it, repository, terminalView.columns, terminalView.rows) } }) { Text(stringResource(R.string.workspace_reconnect)) }
                                    }
                                }
                            }
                        }
                    }
                    RemoteTerminalKeyBar(
                        enabled = state is RemoteTerminalUiState.Connected,
                        view = terminalView,
                    )
                }
            }
        }
        if (showCloseConfirm) {
            AlertDialog(
                onDismissRequest = { showCloseConfirm = false },
                title = { Text(stringResource(R.string.workspace_close_terminal_title)) },
                text = { Text(stringResource(R.string.workspace_close_terminal_message)) },
                confirmButton = { TextButton(onClick = { showCloseConfirm = false; RemoteTerminalRegistry.close(workspace.id); onBack() }) { Text(stringResource(R.string.workspace_disconnect_back)) } },
                dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text(stringResource(R.string.workspace_keep_using)) } },
            )
        }
    }
}

@Composable
private fun RemoteTerminalKeyBar(enabled: Boolean, view: RemoteTerminalView) {
    val workspaceStrings = LocalResources.current
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteTerminalKey(workspaceStrings.getString(R.string.workspace_keyboard), enabled) { view.showKeyboard() }
        RemoteTerminalKey("ESC", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_ESCAPE) }
        RemoteTerminalKey("TAB", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_TAB) }
        RemoteTerminalKey("↑", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP) }
        RemoteTerminalKey("↓", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        RemoteTerminalKey("←", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        RemoteTerminalKey("→", enabled) { view.sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        RemoteTerminalKey("Ctrl-C", enabled) { view.sendBytes(byteArrayOf(3)) }
        RemoteTerminalKey(workspaceStrings.getString(R.string.workspace_paste), enabled) { view.pasteFromClipboard() }
    }
}

@Composable
private fun RemoteTerminalKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}
