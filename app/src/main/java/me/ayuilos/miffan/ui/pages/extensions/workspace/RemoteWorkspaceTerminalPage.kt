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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.RemoteTerminalConnection
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.repository.WorkspaceToolTargetChangedException
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import org.koin.compose.koinInject

private data class RemoteTerminalIdentity(
    val hostId: String,
    val revision: String,
    val hostName: String,
    val endpoint: String,
    val remoteRoot: String,
)

private sealed interface RemoteTerminalUiState {
    data object Connecting : RemoteTerminalUiState
    data object Connected : RemoteTerminalUiState
    data class Disconnected(val exitStatus: Int) : RemoteTerminalUiState
    data class Failed(val message: String) : RemoteTerminalUiState
    data object TargetChanged : RemoteTerminalUiState
    data object HostMissing : RemoteTerminalUiState
}

private sealed interface RemoteTerminalCommand {
    data class Write(val bytes: ByteArray) : RemoteTerminalCommand
    data class Resize(val columns: Int, val rows: Int) : RemoteTerminalCommand
}

/** Owns one SSH session at a time and closes sockets independently of a blocked read coroutine. */
private class RemoteTerminalOwner {
    private val lock = Any()
    private val generation = AtomicInteger(0)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var disposed = false
    private var active: RemoteTerminalConnection? = null

    fun begin(): Int {
        val old: RemoteTerminalConnection?
        val attempt: Int
        synchronized(lock) {
            attempt = generation.incrementAndGet()
            old = active
            active = null
        }
        old?.let(::closeAsync)
        return attempt
    }

    fun install(attempt: Int, connection: RemoteTerminalConnection): Boolean = synchronized(lock) {
        if (disposed || generation.get() != attempt) false else {
            active = connection
            true
        }
    }

    fun isCurrent(attempt: Int): Boolean = synchronized(lock) {
        !disposed && generation.get() == attempt
    }

    fun forget(connection: RemoteTerminalConnection) = synchronized(lock) {
        if (active === connection) active = null
    }

    fun dispose() {
        val old: RemoteTerminalConnection?
        synchronized(lock) {
            disposed = true
            generation.incrementAndGet()
            old = active
            active = null
        }
        old?.let(::closeAsync)
    }

    private fun closeAsync(connection: RemoteTerminalConnection) {
        closeScope.launch { runCatching { connection.close() } }
    }
}

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
    val owner = remember(workspace.id) { RemoteTerminalOwner() }
    val identity = remember(workspace.id) {
        host?.let {
            RemoteTerminalIdentity(
                hostId = it.id,
                revision = it.connectionRevision,
                hostName = it.name,
                endpoint = "${it.username}@${it.host}:${it.port}",
                remoteRoot = workspace.remotePath.orEmpty(),
            )
        }
    }
    val targetChanged = identity != null && (host?.connectionRevision != identity.revision ||
        host?.id != identity.hostId || workspace.remotePath != identity.remoteRoot)
    var reconnectAttempt by remember(workspace.id) { mutableIntStateOf(0) }
    var state by remember(workspace.id) { mutableStateOf<RemoteTerminalUiState>(RemoteTerminalUiState.Connecting) }
    var showCloseConfirm by remember(workspace.id) { mutableStateOf(false) }

    BackHandler { showCloseConfirm = true }
    DisposableEffect(owner) {
        onDispose {
            terminalView.sendBytes = {}
            terminalView.onSizeInCellsChanged = { _, _ -> }
            owner.dispose()
        }
    }

    LaunchedEffect(workspace.id, identity, targetChanged, reconnectAttempt) {
        if (identity == null) {
            state = RemoteTerminalUiState.HostMissing
            return@LaunchedEffect
        }
        if (targetChanged) {
            owner.dispose()
            state = RemoteTerminalUiState.TargetChanged
            return@LaunchedEffect
        }
        val attempt = owner.begin()
        var opened: RemoteTerminalConnection? = null
        var commands: Channel<RemoteTerminalCommand>? = null
        var writer: Job? = null
        try {
            state = RemoteTerminalUiState.Connecting
            terminalView.resetScreen()
            val columns = terminalView.columns
            val rows = terminalView.rows
            val connection = withContext(Dispatchers.IO) {
                repository.openRemoteTerminal(
                    id = workspace.id,
                    columns = columns,
                    rows = rows,
                    expectedHostId = identity.hostId,
                    expectedHostRevision = identity.revision,
                    expectedRemoteRoot = identity.remoteRoot,
                ).also { opened = it }
            }
            if (!isActive || !owner.install(attempt, connection)) return@LaunchedEffect
            val queue = Channel<RemoteTerminalCommand>(Channel.UNLIMITED)
            commands = queue
            terminalView.sendBytes = { bytes ->
                if (owner.isCurrent(attempt)) queue.trySend(RemoteTerminalCommand.Write(bytes))
            }
            terminalView.onSizeInCellsChanged = { columns, rows ->
                if (owner.isCurrent(attempt)) queue.trySend(RemoteTerminalCommand.Resize(columns, rows))
            }
            queue.trySend(RemoteTerminalCommand.Resize(terminalView.columns, terminalView.rows))
            state = RemoteTerminalUiState.Connected

            writer = launch(Dispatchers.IO) {
                try {
                    for (command in queue) {
                        when (command) {
                            is RemoteTerminalCommand.Write -> {
                                connection.output.write(command.bytes)
                                connection.output.flush()
                            }
                            is RemoteTerminalCommand.Resize -> connection.resize(command.columns, command.rows)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (owner.isCurrent(attempt)) {
                            state = RemoteTerminalUiState.Failed(error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_terminal_disconnected))
                        }
                    }
                    runCatching { connection.close() }
                }
            }

            withContext(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                while (isActive && owner.isCurrent(attempt)) {
                    val count = connection.input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val bytes = buffer.copyOf(count)
                        withContext(Dispatchers.Main.immediate) {
                            if (owner.isCurrent(attempt)) terminalView.appendOutput(bytes)
                        }
                    }
                }
            }
            if (owner.isCurrent(attempt) && state is RemoteTerminalUiState.Connected) {
                state = RemoteTerminalUiState.Disconnected(connection.exitStatus)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: WorkspaceToolTargetChangedException) {
            if (owner.isCurrent(attempt)) state = RemoteTerminalUiState.TargetChanged
        } catch (error: Exception) {
            if (owner.isCurrent(attempt)) {
                state = RemoteTerminalUiState.Failed(error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_terminal_connection_failed))
            }
        } finally {
            if (owner.isCurrent(attempt)) {
                terminalView.sendBytes = {}
                terminalView.onSizeInCellsChanged = { _, _ -> }
            }
            commands?.close()
            writer?.cancel()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { opened?.close() }
            }
            opened?.let(owner::forget)
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
                                            is RemoteTerminalUiState.Failed -> current.message
                                            RemoteTerminalUiState.TargetChanged -> workspaceStrings.getString(R.string.workspace_terminal_target_changed)
                                            RemoteTerminalUiState.HostMissing -> workspaceStrings.getString(R.string.workspace_remote_host_config_unavailable)
                                            else -> workspaceStrings.getString(R.string.workspace_disconnected)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (current is RemoteTerminalUiState.Disconnected || current is RemoteTerminalUiState.Failed) {
                                        TextButton(onClick = { reconnectAttempt++ }) { Text(stringResource(R.string.workspace_reconnect)) }
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
                confirmButton = { TextButton(onClick = { showCloseConfirm = false; onBack() }) { Text(stringResource(R.string.workspace_disconnect_back)) } },
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
