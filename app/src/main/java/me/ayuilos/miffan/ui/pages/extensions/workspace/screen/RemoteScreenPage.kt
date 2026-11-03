package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.ui.draw.alpha
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CarouselHorizontal
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MinimizeScreen
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import me.ayuilos.miffan.data.ai.computer.RemoteController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import me.ayuilos.miffan.data.repository.RemoteDesktopProtocol
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.RemoteScreenQuality
import me.ayuilos.miffan.data.repository.RemoteStreamFallbackReason
import me.ayuilos.miffan.data.repository.RemoteStreamPermissions
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.VolumeOff
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
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
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Who the page is about, when the desktop's holder is not known (e.g. while the user holds it). */
    partnerLabel: String? = null,
    /** Easy mode: the partner's face beside the title; the flag is true while it works here. */
    avatar: (@Composable (Modifier, Boolean) -> Unit)? = null,
    /** The partner's current step, shown over the picture while it works. */
    caption: String? = null,
    /** Where the action awaiting the user's yes would act. */
    marker: RemoteScreenMarker? = null,
    /** The partner is waiting for the user's yes on an action here. */
    awaitingApproval: Boolean = false,
    /** Easy mode: the picture keeps the desktop's shape in a card and the rest of the page goes to [bottom]. */
    framed: Boolean = false,
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val partnerWaiting by vm.partnerWaiting.collectAsStateWithLifecycle()
    // A partner turn holds the desktop between its actions too, so the banner follows the whole turn.
    val holder by vm.holder.collectAsStateWithLifecycle()
    val partnerBusy = holder != null
    val assistants = LocalSettings.current.assistants
    val unnamed = stringResource(R.string.assistant_page_default_assistant)
    val holderName = holder?.let { h ->
        assistants.find { it.id.toString() == h.assistantId }?.name?.ifBlank { unnamed } ?: h.name.ifBlank { null }
    }
    val bytes by vm.bytesReceived.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val custom by vm.custom.collectAsStateWithLifecycle()
    val info by vm.connectionInfo.collectAsStateWithLifecycle()
    val relaySnapshots by vm.relaySnapshots.collectAsStateWithLifecycle()
    val stillPicture by vm.stillPicture.collectAsStateWithLifecycle()
    val audio by vm.audio.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    val canUseAutomatic by vm.canUseAutomatic.collectAsStateWithLifecycle()
    val firewallApplying by vm.firewallApplying.collectAsStateWithLifecycle()
    val windowView by vm.windowView.collectAsStateWithLifecycle()
    val windowTarget by vm.target.collectAsStateWithLifecycle()
    val windowIssue by vm.windowIssue.collectAsStateWithLifecycle()
    var windowPicker by remember { mutableStateOf(false) }
    // Full screen draws the zoom and Mac desktop buttons beside the picture, with the other controls.
    val fullscreenZoom = remember { RemoteScreenZoom() }
    var macMenu by remember { mutableStateOf(false) }
    val hints = remember(context) { context.getSharedPreferences(SCREEN_HINTS, Context.MODE_PRIVATE) }
    // The pointer mode is a habit, so the last choice is kept for every screen and every visit.
    var trackpad by remember { mutableStateOf(hints.getBoolean(TRACKPAD_MODE, false)) }
    LaunchedEffect(trackpad) { hints.edit { putBoolean(TRACKPAD_MODE, trackpad) } }
    var keyboard by rememberSaveable { mutableStateOf(false) }
    // Landscape only: where the desktop's frame ends and the keyboard overlay begins, in root pixels.
    var screenBottom by remember { mutableFloatStateOf(0f) }
    var keyboardTop by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val keyboardCover = (screenBottom - keyboardTop).coerceAtLeast(0f)
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var meteredNoticeShown by rememberSaveable { mutableStateOf(false) }
    // Local-network access is asked for only after a stream needed it, then the screen reconnects.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.reconnect()
    }
    var perfOverlay by remember { mutableStateOf(hints.getBoolean(PERF_OVERLAY, false)) }
    val connected = state is RemoteScreenUiState.Connected
    val macOS = platform == RemoteScreenPlatform.MACOS
    // While the partner works, a touch only asks; the second touch takes over (see RemoteScreenTakeoverPrompt).
    val partnerActive = connected && partnerBusy && controller != RemoteController.USER
    var armed by rememberTakeoverArming(partnerActive)
    fun armOrTakeOver() {
        if (armed) { armed = false; vm.takeControl() } else armed = true
    }
    val userHolds = connected && shownController(controller, partnerBusy, partnerWaiting) == RemoteController.USER
    val partnerName = holderName ?: partnerLabel ?: stringResource(R.string.workspace_screen_partner_generic)
    // In landscape a question from the partner would sit in the folded conversation; bring it out.
    LaunchedEffect(awaitingApproval, fullscreen) { if (awaitingApproval && fullscreen) drawer = true }
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
    RemoteScreenFullscreenEffect(fullscreen)
    BackHandler {
        when {
            keyboard -> keyboard = false
            drawer && fullscreen -> drawer = false
            fullscreen -> fullscreen = false
            else -> onBack()
        }
    }
    LaunchedEffect(vm, state) {
        if (state !is RemoteScreenUiState.Connected) keyboard = false
    }
    LaunchedEffect(keyboard) { if (keyboard) vm.prepareTyping() }
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
                    is RemoteScreenNotice.MacShortcutFailed -> snackbar.showSnackbar(resources.getString(
                        if (notice.cuaMissing) R.string.workspace_screen_mac_shortcut_needs_cua else R.string.workspace_screen_mac_shortcut_failed))
                    is RemoteScreenNotice.FirewallFailed -> snackbar.showSnackbar(
                        notice.detail?.takeIf { it.isNotBlank() }?.let { resources.getString(R.string.workspace_screen_firewall_failed_detail, it) }
                            ?: resources.getString(R.string.workspace_screen_firewall_failed),
                        withDismissAction = true, duration = SnackbarDuration.Long)
                    is RemoteScreenNotice.StreamOnMeteredNetwork -> snackbar.showSnackbar(
                        resources.getString(R.string.workspace_screen_stream_metered,
                            remoteScreenQualityLabel(resources, notice.quality), remoteStreamGigabytesPerHour(notice.quality, notice.custom)),
                        withDismissAction = true, duration = SnackbarDuration.Long)
                    is RemoteScreenNotice.StreamFallback -> {
                        val reason = notice.fallback.reason
                        val permission = RemoteStreamPermissions.localNetwork
                            .takeIf { reason == RemoteStreamFallbackReason.LOCAL_NETWORK_PERMISSION }
                        val fixInSettings = onSettings != null && reason in STREAM_SETTINGS_FIXES
                        val result = snackbar.showSnackbar(
                            resources.getString(R.string.workspace_screen_stream_fallback, streamFallbackReason(resources, reason)),
                            actionLabel = when {
                                permission != null -> resources.getString(R.string.workspace_screen_stream_allow_local_network)
                                fixInSettings -> settingsLabel
                                else -> null
                            },
                            withDismissAction = true, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            if (permission != null) localNetwork.launch(permission) else onSettings?.invoke()
                        }
                    }
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
            // Full screen keeps only the floating controls over the desktop.
            if (!fullscreen) TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    avatar?.invoke(Modifier.size(34.dp), partnerActive || awaitingApproval)
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = stringResource(when (state) {
                            is RemoteScreenUiState.Connected -> R.string.im_computer_connected
                            RemoteScreenUiState.Connecting -> R.string.im_computer_connecting
                            else -> R.string.im_computer_disconnected
                        })
                        val mode = info?.takeIf { connected }?.let { connection ->
                            remoteScreenModeLabel(resources, connection.protocol, connection.window).let {
                                if (connection.relayMediaKbps != null) resources.getString(R.string.workspace_screen_mode_relay, it) else it
                            }
                        }
                        // Who drives says more than the connection; the connection's details stay a tap away.
                        val driving = when {
                            !connected -> null
                            userHolds -> stringResource(R.string.workspace_screen_status_user, partnerName)
                            awaitingApproval -> stringResource(R.string.workspace_screen_status_approval, partnerName)
                            partnerBusy -> stringResource(R.string.workspace_screen_status_partner, partnerName)
                            else -> null
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteScreenStatusLine(driving ?: if (mode != null) "$status · $mode · ${bytes.fileSizeToString()}"
                            else stringResource(R.string.im_computer_connection_usage, status, bytes.fileSizeToString()),
                            dot = when {
                                !connected -> MaterialTheme.colorScheme.outline
                                userHolds -> MaterialTheme.colorScheme.primary
                                partnerBusy || awaitingApproval -> MaterialTheme.colorScheme.tertiary
                                else -> CONNECTED_DOT
                            },
                            breathing = state == RemoteScreenUiState.Connecting || (!userHolds && (partnerBusy || awaitingApproval)),
                            // Tapping explains the connection; a long press is the hidden diagnostics switch.
                            modifier = Modifier.weight(1f, fill = false).pointerInput(Unit) {
                                detectTapGestures(onTap = { if (vm.connectionInfo.value != null) details = true }, onLongPress = {
                                    perfOverlay = !perfOverlay
                                    hints.edit { putBoolean(PERF_OVERLAY, perfOverlay) }
                                })
                            })
                        // What the picture is now, a tap from changing it.
                        info?.takeIf { connected }?.let { connection ->
                            remoteScreenQualityTag(resources, quality, connection.protocol, custom, connection.window)
                        }?.let { tag -> RemoteQualityTag(tag, onClick = { qualitySheet = true }) }
                        }
                    }
                    }
                },
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.workspace_screen_actions))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            val protocol = info?.protocol
                            audio?.let { on ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_audio)) },
                                    leadingIcon = { Icon(if (on) HugeIcons.VolumeHigh else HugeIcons.VolumeOff, null) },
                                    trailingIcon = { if (on) Text("✓") },
                                    onClick = { vm.setAudio(!on); menu = false })
                            }
                            // Through a relay Mac and Linux can trade the still pictures for the live picture.
                            if (info?.relayMediaKbps != null && platform != RemoteScreenPlatform.WINDOWS) DropdownMenuItem(
                                text = { Text(stringResource(if (relaySnapshots) R.string.workspace_screen_snapshot_live else R.string.workspace_screen_snapshot_use)) },
                                onClick = { menu = false; vm.useRelaySnapshots(!relaySnapshots) })
                            when {
                                // Windows has no plain connection to switch to.
                                platform == RemoteScreenPlatform.WINDOWS -> Unit
                                protocol == RemoteDesktopProtocol.STREAM -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_screen_stream_skip)) },
                                    onClick = { menu = false; vm.reconnectWithStream(false) })
                                info?.streamSkipped == true || info?.fallback != null -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.workspace_screen_stream_retry)) },
                                    onClick = { menu = false; vm.reconnectWithStream(true) })
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
        val ring by animateColorAsState(when {
            userHolds -> MaterialTheme.colorScheme.primary
            partnerActive || awaitingApproval -> MaterialTheme.colorScheme.tertiary
            else -> Color.Transparent
        }, tween(250), label = "ring")
        val handBackFace = avatar?.let { face -> @Composable { modifier: Modifier -> face(modifier, false) } }
        val screen = @Composable { modifier: Modifier, shape: Shape ->
            Box(modifier.clip(shape).border(2.dp, ring, shape)) {
                // The phone-side zoom buttons would cover much of a framed picture; landscape has the room.
                RemoteScreenViewport(vm, trackpad, Modifier.fillMaxSize(), zoomControls = !framed && !fullscreen,
                    zoom = fullscreenZoom.takeIf { fullscreen },
                    marker = marker?.takeIf { awaitingApproval && !userHolds },
                    blocked = partnerActive, onBlockedTap = ::armOrTakeOver,
                    coveredBottom = if (fullscreen && keyboard) keyboardCover else 0f) { failed ->
                    RemoteScreenFailureContent(
                        state = failed,
                        settingsAvailable = onSettings != null,
                        onRetry = vm::reconnect,
                        onSettings = { onSettings?.invoke() },
                        failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let(failureText),
                        settingsText = settingsLabel,
                        onTrustCertificate = vm::trustCertificate,
                        onUseAutomatic = if (canUseAutomatic) vm::useAutomaticConnection else null,
                        onFixFirewall = vm::applySunshineFirewall,
                        firewallApplying = firewallApplying,
                        onSetUpStream = vm::beginWindowsStreamSetup,
                    )
                }
                RemoteWindowsStreamSetup(vm)
                if (windowTarget != null) windowIssue?.let { issue ->
                    RemoteWindowIssueContent(vm, issue, onPick = { windowPicker = true })
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RemoteStreamSuggestion(vm)
                    if (connected && stillPicture) RemoteScreenStillBadge(onRefresh = vm::refreshPicture)
                }
                RemoteScreenCaption((caption ?: stringResource(R.string.workspace_screen_status_partner, partnerName))
                    .takeIf { partnerActive && !armed }, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                RemoteScreenUserHold(userHolds, holderName ?: partnerLabel, onHandBack = vm::handBackToPartner, avatar = handBackFace)
                if (connected && !fullscreen) IconButton(onClick = { fullscreen = true }, Modifier.align(Alignment.TopEnd)) {
                    ScreenRoundIcon(HugeIcons.Fullscreen, stringResource(R.string.workspace_screen_fullscreen), size = 30.dp)
                }
                RemoteScreenTakeoverPrompt(armed, partnerName)
                if (perfOverlay) RemoteScreenStatsOverlay(vm, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
        val onKeyboard = { if (partnerActive) armOrTakeOver() else if (connected) keyboard = !keyboard }
        val connectedSize = state as? RemoteScreenUiState.Connected
        val ratio = connectedSize?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 10f)
        if (fullscreen) Box(Modifier.fillMaxSize().background(FULLSCREEN_GROUND).padding(padding)) {
            // Landscape: the desktop takes the height, the controls live in the black margins beside it.
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxHeight().width(60.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { fullscreen = false }) {
                        ScreenRoundIcon(HugeIcons.MinimizeScreen, stringResource(R.string.workspace_screen_fullscreen_exit))
                    }
                    avatar?.invoke(Modifier.size(36.dp), partnerActive || awaitingApproval)
                    Spacer(Modifier.weight(1f))
                    if (connected) {
                        IconButton(onClick = { fullscreenZoom.zoomIn() }) {
                            ScreenRoundIcon(HugeIcons.PlusSign, stringResource(R.string.workspace_screen_view_zoom_in))
                        }
                        IconButton(onClick = { fullscreenZoom.zoomOut() }, enabled = fullscreenZoom.canZoomOut,
                            modifier = Modifier.alpha(if (fullscreenZoom.canZoomOut) 1f else 0.4f)) {
                            ScreenRoundIcon(HugeIcons.MinusSign, stringResource(R.string.workspace_screen_view_zoom_out))
                        }
                        // What a four-finger swipe does on the Mac's trackpad, in a menu beside the button.
                        if (macOS) Box {
                            IconButton(onClick = { macMenu = true }) {
                                ScreenRoundIcon(HugeIcons.CarouselHorizontal, stringResource(R.string.workspace_screen_mac_controls), active = macMenu)
                            }
                            DropdownMenu(macMenu, onDismissRequest = { macMenu = false }) {
                                listOf(
                                    Triple(HugeIcons.ArrowLeft01, R.string.workspace_screen_desktop_left, MacShortcut.DESKTOP_LEFT),
                                    Triple(HugeIcons.DashboardSquare01, R.string.workspace_screen_help_mission_control, MacShortcut.MISSION_CONTROL),
                                    Triple(HugeIcons.Copy01, R.string.workspace_screen_help_app_windows, MacShortcut.APP_WINDOWS),
                                    Triple(HugeIcons.ArrowRight01, R.string.workspace_screen_desktop_right, MacShortcut.DESKTOP_RIGHT),
                                ).forEach { (icon, label, shortcut) ->
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) },
                                        onClick = { vm.macShortcut(shortcut) })
                                }
                            }
                        }
                    }
                }
                // A landscape input method takes most of the height. Shrinking the desktop above it would leave a
                // thumbnail, so the keyboard covers the picture's lower part and the picture moves up instead.
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // The frame hugs the desktop rather than the black margins around it.
                    Box(Modifier.fillMaxSize().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        screen(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true)
                            .onGloballyPositioned { screenBottom = it.boundsInRoot().bottom }, RoundedCornerShape(14.dp))
                    }
                    if (keyboard && connected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .onGloballyPositioned { keyboardTop = it.boundsInRoot().top }.imePadding()) {
                        RemoteScreenKeyboard(vm, platform, compact = true)
                    }
                }
                Column(Modifier.fillMaxHeight().width(60.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { trackpad = !trackpad }, enabled = connected) {
                        ScreenRoundIcon(if (trackpad) HugeIcons.Touchpad01 else HugeIcons.Touch01,
                            stringResource(if (trackpad) R.string.workspace_screen_mode_trackpad else R.string.workspace_screen_mode_direct))
                    }
                    IconButton(onClick = onKeyboard, enabled = connected) {
                        ScreenRoundIcon(HugeIcons.Keyboard, stringResource(R.string.workspace_screen_keyboard), active = keyboard)
                    }
                    audio?.let { on ->
                        IconButton(onClick = { vm.setAudio(!on) }) {
                            ScreenRoundIcon(if (on) HugeIcons.VolumeHigh else HugeIcons.VolumeOff, stringResource(R.string.workspace_screen_audio))
                        }
                    }
                    if (windowView) IconButton(onClick = { windowPicker = true }) {
                        Surface(color = if (windowTarget != null) MaterialTheme.colorScheme.primary else Color(0x8C2A2E35),
                            contentColor = if (windowTarget != null) MaterialTheme.colorScheme.onPrimary else Color.White,
                            shape = CircleShape, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { RemoteWindowSideIcon() }
                        }
                    }
                    if (framed) IconButton(onClick = { drawer = true }) {
                        ScreenRoundIcon(HugeIcons.Message01, stringResource(R.string.workspace_screen_open_chat), active = drawer)
                    }
                }
            }
            // The conversation slides over the desktop's right side and folds away again.
            AnimatedVisibility(framed && drawer, Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                enter = slideInHorizontally(tween(220)) { it }, exit = slideOutHorizontally(tween(180)) { it }) {
                Surface(Modifier.fillMaxHeight().width(360.dp).imePadding(), shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp) {
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { drawer = false }) {
                                Icon(HugeIcons.ArrowRight01, stringResource(R.string.workspace_screen_close_chat))
                            }
                        }
                        bottom()
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (windowView) RemoteWindowBar(vm, onPick = { windowPicker = true }, onFit = { vm.toggleFit(PORTRAIT_FIT_ASPECT) },
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            // A window reshaped for the phone is taller than wide; a desktop never is.
            val minRatio = if (windowTarget != null) 0.5f else 1f
            if (framed) screen(Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(ratio.coerceIn(minRatio, 2.4f)), RoundedCornerShape(18.dp))
            else screen(Modifier.weight(1f).fillMaxWidth(), RectangleShape)
            RemoteScreenDock(trackpad, onTrackpad = { trackpad = it }, keyboard, onKeyboard = onKeyboard, enabled = connected,
                audio = audio, onAudio = vm::setAudio)
            if (keyboard && connected) RemoteScreenKeyboard(vm, platform)
            bottom()
        }
    }
    info?.takeIf { details }?.let { RemoteConnectionDetailsDialog(it, bytes, onDismiss = { details = false }) }
    if (windowPicker) RemoteWindowPicker(vm, onDismiss = { windowPicker = false })
    if (help) RemoteScreenGestureHelp(trackpad = trackpad, macOS = macOS, onDismiss = { help = false })
    info?.takeIf { qualitySheet }?.let { connection ->
        RemoteQualitySheet(connection.protocol, connection.window, quality, custom, onDismiss = { qualitySheet = false }, onPick = { level ->
            qualitySheet = false
            // The level already in use changes nothing, so a stream's custom choice survives a look at one window.
            if (level == quality.effectiveFor(connection.protocol)) Unit
            else if (level == RemoteScreenQuality.CUSTOM) customDialog = true else vm.setQuality(level)
        })
    }
    if (customDialog) RemoteStreamCustomDialog(custom, onConfirm = { customDialog = false; vm.setCustom(it) },
        onDismiss = { customDialog = false })
}

/** Fallbacks the user can fix from the computer's screen settings (pairing, encryption, Sunshine itself). */
private val STREAM_SETTINGS_FIXES = setOf(
    RemoteStreamFallbackReason.SUNSHINE_MISSING, RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING,
    RemoteStreamFallbackReason.NOT_PAIRED, RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED,
    RemoteStreamFallbackReason.MAC_PERMISSIONS,
)

private const val PERF_OVERLAY = "perf_overlay"
/** The shape "fit to phone" gives a window in portrait: about the picture card's, width over height. */
private const val PORTRAIT_FIT_ASPECT = 0.76f
private const val TRACKPAD_MODE = "trackpad_mode"
private val CONNECTED_DOT = Color(0xFF2E9E5B)
private val FULLSCREEN_GROUND = Color(0xFF0B0C0E)

/** A control over or beside the desktop: a dark translucent disc that reads on any picture. */
@Composable
private fun ScreenRoundIcon(icon: ImageVector, description: String, active: Boolean = false, size: Dp = 40.dp) {
    Surface(color = if (active) MaterialTheme.colorScheme.primary else Color(0x8C2A2E35),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White, shape = CircleShape, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(size * 0.5f)) }
    }
}

/**
 * Over a still picture through a relay, which stopped refreshing to save the computer's relay
 * allowance: a dark pill like the other on-picture controls, tapped to fetch a new picture.
 */
@Composable
private fun RemoteScreenStillBadge(onRefresh: () -> Unit) {
    Surface(onClick = onRefresh, color = Color(0x8C2A2E35), contentColor = Color.White, shape = CircleShape,
        modifier = Modifier.padding(top = 4.dp)) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(HugeIcons.Refresh01, null, Modifier.size(16.dp))
            Text(stringResource(R.string.workspace_screen_still_refresh), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The line under the title: who drives, or the connection; a breathing dot while something is under way. */
@Composable
private fun RemoteScreenStatusLine(text: String, dot: Color, breathing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (breathing) {
        val transition = rememberInfiniteTransition(label = "status")
        transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot").value
    } else 1f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DockChoice(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp, modifier = Modifier.semantics { this.selected = selected }) {
        Row(Modifier.height(34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** How touches reach the computer, and the keyboard: right under the picture, where the thumb already is. */
@Composable
private fun RemoteScreenDock(trackpad: Boolean, onTrackpad: (Boolean) -> Unit, keyboard: Boolean, onKeyboard: () -> Unit, enabled: Boolean,
    audio: Boolean? = null, onAudio: (Boolean) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
            Row(Modifier.padding(3.dp)) {
                DockChoice(HugeIcons.Touch01, stringResource(R.string.workspace_screen_touch_direct), !trackpad, enabled) { onTrackpad(false) }
                DockChoice(HugeIcons.Touchpad01, stringResource(R.string.workspace_screen_touch_trackpad), trackpad, enabled) { onTrackpad(true) }
            }
        }
        Spacer(Modifier.weight(1f))
        // Sound (high-performance mode only): its state shows here, not only in the menu.
        audio?.let { on ->
            FilledTonalIconToggleButton(checked = on, onCheckedChange = onAudio, enabled = enabled) {
                Icon(if (on) HugeIcons.VolumeHigh else HugeIcons.VolumeOff, stringResource(R.string.workspace_screen_audio))
            }
            Spacer(Modifier.width(8.dp))
        }
        FilledTonalIconToggleButton(checked = keyboard, onCheckedChange = { onKeyboard() }, enabled = enabled) {
            Icon(HugeIcons.Keyboard, stringResource(R.string.workspace_screen_keyboard))
        }
    }
}

