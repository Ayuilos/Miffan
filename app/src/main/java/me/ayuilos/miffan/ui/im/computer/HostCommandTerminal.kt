package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteTerminalConnection
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.pages.extensions.workspace.RemoteTerminalKeyBar
import me.ayuilos.miffan.ui.pages.extensions.workspace.RemoteTerminalScreen
import me.ayuilos.miffan.ui.pages.extensions.workspace.RemoteTerminalView
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import org.koin.compose.koinInject

private sealed interface HostCommandState {
    data object Connecting : HostCommandState
    data object Running : HostCommandState
    data class Finished(val exitCode: Int) : HostCommandState
    data class Failed(val error: Throwable) : HostCommandState
}

/**
 * Runs one fixed [command] on the computer [hostId] in a terminal on the phone, over the saved SSH
 * connection: the user sees exactly what runs and types any password (sudo) right there. [onFinished]
 * gets whether it exited with 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostCommandTerminal(hostId: String, command: String, onFinished: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val workspaces: WorkspaceRepository = koinInject()
    val screen = remember { RemoteTerminalScreen() }
    // This terminal only takes sudo's password: the IME's password keyboard, not composing CJK input.
    val view = remember { RemoteTerminalView(context).apply { passwordInput = true } }
    var state by remember { mutableStateOf<HostCommandState>(HostCommandState.Connecting) }
    var connection by remember { mutableStateOf<RemoteTerminalConnection?>(null) }
    // The view's size in cells, kept until the terminal exists and applied to it then.
    val size = remember { intArrayOf(80, 24) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(hostId, command) {
        screen.appendOutput("$ $command\r\n".toByteArray())
        try {
            val opened = workspaces.openHostCommandTerminal(hostId, command, size[0], size[1])
            connection = opened
            state = HostCommandState.Running
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = opened.input.read(buffer)
                    if (count < 0) break
                    val chunk = buffer.copyOf(count)
                    withContext(Dispatchers.Main) { screen.appendOutput(chunk) }
                }
            }
            val exit = opened.exitStatus
            state = HostCommandState.Finished(exit)
            onFinished(exit == 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state = HostCommandState.Failed(error)
        } finally {
            val opened = connection
            connection = null
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { opened?.close() } }
        }
    }
    DisposableEffect(view) {
        view.screen = screen
        view.sendBytes = { bytes ->
            val opened = connection
            if (opened != null) scope.launch(Dispatchers.IO) {
                runCatching { opened.output.write(bytes); opened.output.flush() }
            }
        }
        // Never on the main thread: a JSch write there fails half-way and breaks the whole SSH session
        // (this is what ended the terminal as soon as the keyboard opened).
        view.onSizeInCellsChanged = { columns, rows ->
            size[0] = columns
            size[1] = rows
            connection?.let { opened -> scope.launch(Dispatchers.IO) { runCatching { opened.resize(columns, rows) } } }
        }
        onDispose {
            view.sendBytes = {}
            view.onSizeInCellsChanged = { _, _ -> }
            view.detachScreen()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MiffanTheme(colorMode = ColorMode.DARK) {
            val status = when (val current = state) {
                HostCommandState.Connecting -> stringResource(R.string.im_space_run_connecting)
                HostCommandState.Running -> stringResource(R.string.im_space_run_running)
                is HostCommandState.Finished -> if (current.exitCode == 0) stringResource(R.string.im_space_run_ok)
                    else stringResource(R.string.im_space_run_failed, current.exitCode)
                is HostCommandState.Failed -> computerErrorMessage(context.resources, current.error)
            }
            Scaffold(topBar = {
                // The status sits in the bar: the terminal view paints over anything placed right above it.
                TopAppBar(title = {
                    Column {
                        Text(stringResource(R.string.im_space_run_title))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2)
                    }
                }, navigationIcon = {
                    TextButton(onClick = onDismiss) { Text(stringResource(if (state is HostCommandState.Finished) R.string.im_computer_done else R.string.common_cancel)) }
                })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().background(Color.Black)) {
                    if (state == HostCommandState.Connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                    RemoteTerminalKeyBar(enabled = state == HostCommandState.Running, view = view)
                }
            }
        }
    }
}
