package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Touch01
import me.rerere.hugeicons.stroke.Touchpad01
import org.koin.androidx.compose.koinViewModel

@Composable
fun RemoteScreenPage(id: String, vm: RemoteScreenVM) {
    val nav = LocalNavController.current
    val workspaceVM: WorkspaceVM = koinViewModel()
    val workspaces by workspaceVM.workspaces.collectAsStateWithLifecycle()
    val hosts by workspaceVM.hosts.collectAsStateWithLifecycle()
    val hostId = workspaces.find { it.id == id }?.remoteHostId
    val host = hosts.find { it.id == hostId }
    var settings by remember { mutableStateOf(false) }
    RemoteScreenScaffold(
        vm = vm,
        title = host?.name ?: stringResource(R.string.workspace_screen_title),
        settingsLabel = stringResource(R.string.workspace_screen_settings),
        onSettings = if (host != null) ({ settings = true }) else null,
        onBack = { nav.popBackStack() },
    )
    if (settings && host != null) {
        RemoteHostScreenSettingsDialog(host, workspaceVM, onDismiss = { settings = false }, onSaved = vm::reconnect)
    }
}

/**
 * The one remote screen page. Professional mode shows it as is; easy mode adds the partner's
 * conversation through [bottom] and points [onSettings] at its guided setup. Hide features for
 * easy mode here, behind parameters, rather than building a second page.
 */
@Composable
internal fun RemoteScreenScaffold(
    vm: RemoteScreenVM,
    /** The computer's name. */
    title: String,
    settingsLabel: String,
    /** Null disables the settings entry (and the failure panel's settings button). */
    onSettings: (() -> Unit)?,
    onBack: () -> Unit,
    /** Overrides the failure panel's explanation, e.g. with easy mode wording. */
    failureText: (Throwable) -> String? = { null },
    /** The partner's turn is still running on this computer (see [RemoteScreenControllerBanner]). */
    partnerBusy: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val bytes by vm.bytesReceived.collectAsStateWithLifecycle()
    val fps by vm.maxFps.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    var trackpad by rememberSaveable { mutableStateOf(false) }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var meteredNoticeShown by rememberSaveable { mutableStateOf(false) }
    val hints = remember(context) { context.getSharedPreferences(SCREEN_HINTS, Context.MODE_PRIVATE) }
    var perfOverlay by remember { mutableStateOf(hints.getBoolean(PERF_OVERLAY, false)) }
    val connected = state is RemoteScreenUiState.Connected
    val macOS = platform == RemoteScreenPlatform.MACOS
    // Each input mode explains its gestures once, the first time it is used on a live screen.
    LaunchedEffect(connected, trackpad) {
        if (!connected) return@LaunchedEffect
        val key = if (trackpad) "help_seen_trackpad" else "help_seen_direct"
        if (!hints.getBoolean(key, false)) {
            hints.edit { putBoolean(key, true) }
            help = true
        }
    }

    RemoteScreenVisibility(vm)
    BackHandler { if (keyboard) keyboard = false else onBack() }
    LaunchedEffect(vm, state) {
        if (state !is RemoteScreenUiState.Connected) keyboard = false
    }
    LaunchedEffect(vm) {
        if (vm.metered && !meteredNoticeShown) {
            meteredNoticeShown = true
            launch { snackbar.showSnackbar(resources.getString(R.string.workspace_screen_metered)) }
        }
        vm.notices.collect { notice ->
            launch {
                when (notice) {
                    RemoteScreenNotice.TextNotTypable -> snackbar.showSnackbar(resources.getString(R.string.workspace_screen_text_unsupported))
                    is RemoteScreenNotice.ClipboardFailed -> snackbar.showSnackbar(resources.getString(R.string.workspace_screen_paste_failed))
                    // Copying to the phone is the user's call: the computer's clipboard can hold anything.
                    is RemoteScreenNotice.RemoteClipboard -> {
                        val result = snackbar.showSnackbar(resources.getString(R.string.workspace_screen_clipboard_new),
                            actionLabel = resources.getString(R.string.workspace_screen_clipboard_copy), withDismissAction = true)
                        if (result == SnackbarResult.ActionPerformed) {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(resources.getString(R.string.workspace_screen_title), notice.text))
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.im_computer_connection_usage,
                            stringResource(when (state) {
                                is RemoteScreenUiState.Connected -> R.string.im_computer_connected
                                RemoteScreenUiState.Connecting -> R.string.im_computer_connecting
                                else -> R.string.im_computer_disconnected
                            }), bytes.fileSizeToString()),
                            style = MaterialTheme.typography.bodySmall,
                            // Hidden diagnostics switch: no menu entry, since few people need it.
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = {
                                    perfOverlay = !perfOverlay
                                    hints.edit { putBoolean(PERF_OVERLAY, perfOverlay) }
                                })
                            })
                    }
                },
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    IconButton(enabled = connected, onClick = { trackpad = !trackpad }) {
                        Icon(if (trackpad) HugeIcons.Touchpad01 else HugeIcons.Touch01,
                            contentDescription = stringResource(if (trackpad) R.string.workspace_screen_mode_trackpad else R.string.workspace_screen_mode_direct),
                            tint = if (trackpad) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(enabled = connected, onClick = { keyboard = !keyboard }) {
                        Icon(HugeIcons.Keyboard, contentDescription = stringResource(R.string.workspace_screen_keyboard),
                            tint = if (keyboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.foundation.layout.Box {
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_help)) },
                                onClick = { menu = false; help = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_reconnect)) },
                                onClick = { menu = false; vm.reconnect() })
                            DropdownMenuItem(text = { Text(settingsLabel) },
                                enabled = onSettings != null, onClick = { menu = false; onSettings?.invoke() })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RemoteScreenViewport(vm, trackpad, Modifier.fillMaxSize()) { failed ->
                    RemoteScreenFailureContent(
                        state = failed,
                        settingsAvailable = onSettings != null,
                        onRetry = vm::reconnect,
                        onSettings = { onSettings?.invoke() },
                        failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let(failureText),
                        settingsText = settingsLabel,
                    )
                }
                RemoteScreenControllerBanner(controller, onHandBack = vm::handBackToPartner, partnerBusy = partnerBusy,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                if (perfOverlay) RemoteScreenStatsOverlay(vm, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            if (keyboard && connected) RemoteScreenKeyboard(vm, macOS = macOS)
            bottom()
        }
    }
    if (help) RemoteScreenGestureHelp(trackpad = trackpad, macOS = macOS, onDismiss = { help = false })
}

private const val SCREEN_HINTS = "remote_screen_hints"
private const val PERF_OVERLAY = "perf_overlay"

