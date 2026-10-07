package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Mouse01
import me.rerere.hugeicons.stroke.Computer
import org.koin.androidx.compose.koinViewModel

@Composable
fun RemoteScreenPage(id: String, vm: RemoteScreenVM) {
    val nav = LocalNavController.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val workspaceVM: WorkspaceVM = koinViewModel()
    val workspaces by workspaceVM.workspaces.collectAsStateWithLifecycle()
    val hosts by workspaceVM.hosts.collectAsStateWithLifecycle()
    val hostId = workspaces.find { it.id == id }?.remoteHostId
    val host = hosts.find { it.id == hostId }
    val state by vm.state.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val bitmap by vm.bitmap.collectAsStateWithLifecycle()
    // Keep this as State and read its value only in Canvas's drawing scope.
    val frameVersion = vm.frameVersion.collectAsState()
    val cursor = vm.cursor.collectAsState()
    val bytes by vm.bytesReceived.collectAsStateWithLifecycle()
    var trackpad by rememberSaveable(id) { mutableStateOf(false) }
    var keyboard by rememberSaveable(id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var meteredNoticeShown by rememberSaveable(id) { mutableStateOf(false) }
    val fps by vm.maxFps.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val connected = state is RemoteScreenUiState.Connected

    BackHandler(enabled = keyboard) { keyboard = false }
    DisposableEffect(vm, lifecycle, view) {
        val previousKeepScreenOn = view.keepScreenOn
        fun updateVisibility(visible: Boolean) {
            vm.setVisible(visible)
            view.keepScreenOn = visible || previousKeepScreenOn
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> updateVisibility(true)
                Lifecycle.Event.ON_STOP -> updateVisibility(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        updateVisibility(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setVisible(false)
            view.keepScreenOn = previousKeepScreenOn
        }
    }
    LaunchedEffect(vm, state) {
        if (state !is RemoteScreenUiState.Connected) keyboard = false
    }
    LaunchedEffect(vm) {
        if (vm.metered && !meteredNoticeShown) {
            meteredNoticeShown = true
            launch { snackbar.showSnackbar(resources.getString(R.string.workspace_screen_metered)) }
        }
        vm.notices.collect { notice ->
            val text = when (notice) {
                RemoteScreenNotice.TextNotTypable -> resources.getString(R.string.workspace_screen_text_unsupported)
                is RemoteScreenNotice.ClipboardFailed -> resources.getString(R.string.workspace_screen_paste_failed)
                is RemoteScreenNotice.RemoteClipboard -> {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText(resources.getString(R.string.workspace_screen_title), notice.text),
                    )
                    resources.getString(R.string.workspace_screen_clipboard_copied)
                }
            }
            launch { snackbar.showSnackbar(text) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text((state as? RemoteScreenUiState.Connected)?.name?.takeIf { it.isNotBlank() }
                            ?: host?.name ?: stringResource(R.string.workspace_screen_title),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.workspace_screen_usage, bytes.fileSizeToString()),
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { BackButton(onClick = { nav.popBackStack() }) },
                actions = {
                    IconButton(enabled = connected, onClick = { trackpad = !trackpad }) {
                        Icon(if (trackpad) HugeIcons.Mouse01 else HugeIcons.Computer,
                            contentDescription = stringResource(if (trackpad) R.string.workspace_screen_mode_trackpad else R.string.workspace_screen_mode_direct),
                            tint = if (trackpad) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = connected, onClick = { keyboard = !keyboard }) {
                        Icon(HugeIcons.Keyboard, contentDescription = stringResource(R.string.workspace_screen_keyboard),
                            tint = if (keyboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.workspace_screen_actions))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            listOf(5 to R.string.workspace_screen_fps_saver, 10 to R.string.workspace_screen_fps_standard,
                                20 to R.string.workspace_screen_fps_smooth).forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) },
                                    trailingIcon = { if (fps == value) Text("✓") },
                                    onClick = { vm.setMaxFps(value); menu = false })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_reconnect)) },
                                onClick = { menu = false; vm.reconnect() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_settings)) },
                                enabled = host != null, onClick = { menu = false; settings = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            RemoteScreenControllerBanner(controller, onHandBack = vm::handBackToPartner)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (connected && bitmap != null) {
                    RemoteScreenCanvas(requireNotNull(bitmap), frameVersion, cursor, trackpad, vm,
                        Modifier.fillMaxSize())
                }
                when (val current = state) {
                    RemoteScreenUiState.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is RemoteScreenUiState.Connected -> Unit
                    else -> RemoteScreenFailureContent(
                        state = current,
                        settingsAvailable = host != null,
                        onRetry = vm::reconnect,
                        onSettings = { settings = true },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            if (keyboard && connected) RemoteScreenKeyboard(vm, macOS = platform == RemoteScreenPlatform.MACOS)
        }
    }
    if (settings && host != null) {
        RemoteHostScreenSettingsDialog(host, workspaceVM, onDismiss = { settings = false }, onSaved = vm::reconnect)
    }
}
