package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessRegistration
import me.rerere.workspace.WorkspaceSessionLease
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(id: String) {
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val workspaceManager = koinInject<WorkspaceManager>()
    val state by vm.state.collectAsStateWithLifecycle()

    MiffanTheme(colorMode = ColorMode.DARK) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.workspace?.name?.let { stringResource(R.string.workspace_terminal_title_with_name, it) } ?: stringResource(R.string.workspace_terminal_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton() },
                )
            },
        ) { innerPadding ->
            WorkspaceTerminalContent(
                root = state.workspace?.root,
                contentPadding = innerPadding,
                workspaceManager = workspaceManager,
            )
        }
    }
}

@Composable
private fun WorkspaceTerminalContent(
    root: String?,
    contentPadding: PaddingValues,
    workspaceManager: WorkspaceManager,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val scope = rememberCoroutineScope()
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    var finished by remember(root) { mutableStateOf(false) }
    var resourceError by remember(root) { mutableStateOf<String?>(null) }
    var activeLease by remember(root) { mutableStateOf<WorkspaceSessionLease?>(null) }
    var activeProcessRegistration by remember(root) {
        mutableStateOf<WorkspaceProcessRegistration?>(null)
    }
    var controlDown by remember(root) { mutableStateOf(false) }
    var altDown by remember(root) { mutableStateOf(false) }
    val sessionClient = remember(root, workspaceManager) {
        WorkspaceTerminalSessionClient(context.applicationContext) {
            scope.launch {
                val finishedLease = activeLease
                val finishedRegistration = activeProcessRegistration
                val error = root?.let { current ->
                    withContext(Dispatchers.IO) {
                        runCatching { workspaceManager.checkResourceLimits(current) }.exceptionOrNull()
                    }
                }
                if (error != null) {
                    resourceError = error.message ?: "Workspace resource limit exceeded"
                }
                finishedRegistration?.close()
                if (activeProcessRegistration === finishedRegistration) {
                    activeProcessRegistration = null
                }
                finishedLease?.close()
                if (activeLease === finishedLease) activeLease = null
                finished = true
            }
        }
    }
    val viewClient = remember(root) {
        WorkspaceTerminalViewClient(context)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    val sessionState by produceState<TerminalSessionUiState>(
        initialValue = TerminalSessionUiState.Loading,
        root,
        sessionClient,
        workspaceManager,
    ) {
        val current = root
        if (current == null) {
            value = TerminalSessionUiState.Loading
            return@produceState
        }
        if (!withContext(Dispatchers.IO) { workspaceManager.hasRootfs(current) }) {
            value = TerminalSessionUiState.NotInstalled
            return@produceState
        }
        val lease = try {
            withContext(Dispatchers.IO) {
                workspaceManager.tryAcquireInteractiveSession(current)
            }
        } catch (error: Throwable) {
            value = TerminalSessionUiState.Failed(error.message ?: "Workspace resource check failed")
            return@produceState
        }
        if (lease == null) {
            value = TerminalSessionUiState.Busy
            return@produceState
        }
        activeLease = lease
        try {
            // Rootfs patching and DNS lookup are blocking I/O. Admission is acquired first so an
            // install or AI command cannot race terminal preparation.
            withContext(Dispatchers.IO) {
                prepareWorkspaceTerminalSession(context, current)
                workspaceManager.checkResourceLimits(current)
            }
            if (!isActive) {
                lease.close()
                activeLease = null
                return@produceState
            }
            val created = createWorkspaceTerminalSession(
                context = context,
                root = current,
                client = sessionClient,
                resourceLimits = workspaceManager.resourceLimits,
                bindMounts = workspaceManager.executionBindMounts,
            )
            val registration = try {
                // Once the native process exists, registration must either publish its durable
                // ownership record or kill it. Do not let Compose cancellation drop the result in
                // the narrow interval between those two states.
                withContext(NonCancellable + Dispatchers.IO) {
                    workspaceManager.registerInteractiveProcess(
                        root = current,
                        pid = created.pid.toLong(),
                        commandIdentity = workspaceTerminalCommandIdentity(context),
                    )
                }
            } catch (error: Throwable) {
                created.finishWorkspaceProcessGroup(registration = null)
                throw error
            }
            activeProcessRegistration = registration
            if (!isActive) {
                created.finishWorkspaceProcessGroup(registration)
                registration.close()
                activeProcessRegistration = null
                lease.close()
                activeLease = null
                return@produceState
            }
            value = TerminalSessionUiState.Ready(created)
        } catch (error: Throwable) {
            activeProcessRegistration?.terminate(graceful = false)
            activeProcessRegistration?.close()
            activeProcessRegistration = null
            lease.close()
            activeLease = null
            value = TerminalSessionUiState.Failed(error.message ?: "Failed to start terminal")
        }
    }

    DisposableEffect(root) {
        onDispose {
            activeLease?.close()
            activeLease = null
        }
    }

    val currentState = sessionState
    if (currentState !is TerminalSessionUiState.Ready) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (currentState) {
                    TerminalSessionUiState.NotInstalled ->
                        stringResource(R.string.workspace_terminal_not_installed)
                    TerminalSessionUiState.Busy ->
                        stringResource(R.string.workspace_terminal_busy)
                    is TerminalSessionUiState.Failed -> currentState.message
                    else -> stringResource(R.string.workspace_terminal_loading)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    val session = currentState.session
    val resourceGuard = remember(session, root) {
        workspaceManager.createResourceGuard(requireNotNull(root))
    }

    LaunchedEffect(session, resourceGuard) {
        while (isActive && !finished) {
            delay(RESOURCE_MONITOR_INTERVAL_MS)
            val error = withContext(Dispatchers.IO) {
                runCatching { resourceGuard.check() }.exceptionOrNull()
            }
            if (error != null) {
                resourceError = error.message ?: "Workspace resource limit exceeded"
                session.finishWorkspaceProcessGroup(activeProcessRegistration)
                break
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            sessionClient.terminalView = null
            viewClient.terminalView = null
            val registration = activeProcessRegistration
            session.finishWorkspaceProcessGroup(registration)
            registration?.close()
            if (activeProcessRegistration === registration) {
                activeProcessRegistration = null
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTextSize(terminalTextSizePx)
                            setTypeface(terminalTypeface)
                            setTerminalViewClient(viewClient)
                            attachSession(session)
                            sessionClient.terminalView = this
                            viewClient.terminalView = this
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    viewClient.focusAndShowKeyboard()
                                }
                                false
                            }
                            post {
                                viewClient.focusAndShowKeyboard()
                            }
                        }
                    },
                    update = { terminalView ->
                        terminalView.isFocusable = true
                        terminalView.isFocusableInTouchMode = true
                        terminalView.setTextSize(terminalTextSizePx)
                        terminalView.setTypeface(terminalTypeface)
                        terminalView.setTerminalViewClient(viewClient)
                        sessionClient.terminalView = terminalView
                        viewClient.terminalView = terminalView
                        terminalView.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                viewClient.focusAndShowKeyboard()
                            }
                            false
                        }
                        terminalView.attachSession(session)
                        terminalView.onScreenUpdated()
                    },
                )
                if (finished) {
                    Text(
                        text = resourceError
                            ?.let { "${stringResource(R.string.workspace_terminal_resource_limit)}: $it" }
                            ?: stringResource(R.string.workspace_terminal_exited),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
            TerminalExtraKeysBar(
                controlDown = controlDown,
                altDown = altDown,
                onControlToggle = { controlDown = !controlDown },
                onAltToggle = { altDown = !altDown },
                onSendText = { session.writeText(it) },
            )
        }
    }
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        },
    )
}

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}

private sealed interface TerminalSessionUiState {
    data object Loading : TerminalSessionUiState
    data object NotInstalled : TerminalSessionUiState
    data object Busy : TerminalSessionUiState
    data class Failed(val message: String) : TerminalSessionUiState
    data class Ready(val session: TerminalSession) : TerminalSessionUiState
}

private const val RESOURCE_MONITOR_INTERVAL_MS = 250L
