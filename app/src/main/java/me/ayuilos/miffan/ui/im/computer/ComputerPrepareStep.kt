package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.streamPairingErrorText
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenRdpInstallGuidance
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.rdpServer
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.usesRdp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.computer.CuaUpdateState
import me.ayuilos.miffan.data.repository.RemoteSunshinePermission
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenLog
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenVncInstallGuidance
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamEnableFlow
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupPhase
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteStreamSetupState
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteSunshinePermissions
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.remoteScreenSetupError
import androidx.compose.ui.platform.LocalResources
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.InformationCircle

private const val QUIET_RECHECK_INTERVAL_MS = 5_000L

/**
 * Getting the computer ready, as one list that fills itself: what Miffan can do runs by itself, and
 * the one thing that needs the user carries its button right there. Version numbers, server names
 * and commands stay behind "details".
 */
@Composable
internal fun ComputerReadyStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    var installCommand by remember(state.hostId) { mutableStateOf<String?>(null) }
    var confirmUpdate by remember(state.hostId) { mutableStateOf(false) }
    val update by vm.cuaUpdate.collectAsStateWithLifecycle()
    val partnerUsing by vm.partnerUsing.collectAsStateWithLifecycle()
    val statusPage = vm.statusPage
    // Finished updates notify only when the user is not already looking.
    if (statusPage) LifecycleResumeEffect(state.hostId) {
        val stop = vm.watchUpdates()
        onPauseOrDispose { stop() }
    }
    var askMacAccount by remember(state.hostId) { mutableStateOf(false) }
    val streamSetups by vm.streamSetups.collectAsStateWithLifecycle()
    val stream = state.hostId?.let { streamSetups[it] }?.takeIf { it.connectionRevision == state.hostRevision }
    var enablingStream by remember(state.hostId) { mutableStateOf(false) }
    val nav = LocalNavController.current
    val probe = state.probe
    // The user is likely fixing something at the computer: pick it up without asking for a tap.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, state.ready, state.usesScreen, probe != null) {
        if (state.ready || !state.usesScreen || probe == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(QUIET_RECHECK_INTERVAL_MS)
                vm.quietRecheck()
            }
        }
    }
    val name = state.target?.name.orEmpty()
    val retry = if (probe == null && !state.busy) SetupAction(stringResource(R.string.im_computer_retry)) {
        state.hostId?.let { vm.chooseHost(it, fromList = true) }
    } else null
    SetupScreen(
        title = when {
            !statusPage -> if (state.ready) stringResource(R.string.im_computer_ready_done_title, name) else stringResource(R.string.im_computer_ready_title, name)
            probe == null -> stringResource(if (state.busy) R.string.im_computer_status_checking else R.string.im_computer_status_unreachable)
            update is CuaUpdateState.Running -> stringResource(R.string.im_computer_status_updating)
            state.pendingCount > 0 -> pluralStringResource(R.plurals.im_computer_status_pending, state.pendingCount, state.pendingCount)
            state.cuaUpdateTo != null -> stringResource(R.string.im_computer_status_usable)
            else -> stringResource(R.string.im_computer_status_fine)
        },
        subtitle = when {
            !statusPage -> stringResource(when {
                probe == null -> R.string.im_computer_ready_connecting_help
                state.ready -> R.string.im_computer_ready_done_help
                else -> R.string.im_computer_ready_help
            })
            probe == null -> if (state.busy) null else stringResource(R.string.im_computer_status_unreachable_help)
            update is CuaUpdateState.Running -> stringResource(R.string.im_computer_status_updating_help)
            state.pendingCount > 0 -> stringResource(R.string.im_computer_status_pending_help)
            state.cuaUpdateTo != null -> stringResource(R.string.im_computer_status_usable_help)
            state.busy -> null
            else -> stringResource(R.string.im_computer_status_fine_help)
        },
        eyebrow = name.takeIf { statusPage },
        state = state, onDismissError = vm::dismissError,
        // A status page reports and fixes in place; it never leads on to sharing.
        primary = when {
            retry != null -> retry
            statusPage -> null
            else -> SetupAction(stringResource(R.string.im_computer_continue), enabled = state.ready, onClick = vm::continueToShare)
        },
        secondary = when {
            probe == null || statusPage -> null
            state.usesScreen -> SetupAction(stringResource(R.string.im_computer_no_screen)) { vm.setWantsScreen(false) }
            else -> SetupAction(stringResource(R.string.im_computer_use_screen_again)) { vm.setWantsScreen(true) }
        },
    ) {
        if (statusPage && state.busy && probe != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        SetupGroup {
            ChecklistRow(if (probe != null) CheckState.DONE else if (state.busy) CheckState.WAITING else CheckState.TODO,
                stringResource(if (probe != null) R.string.im_computer_check_connected else R.string.im_computer_check_connecting),
                divider = probe != null)
            if (probe == null) return@SetupGroup
            if (!state.usesScreen) {
                ChecklistRow(CheckState.DONE, stringResource(R.string.im_computer_check_commands_only),
                    stringResource(R.string.im_computer_use_screen_off), divider = false)
                return@SetupGroup
            }
            ScreenRow(vm, state, stream, enablingStream = enablingStream, onEnableStream = { enablingStream = true })
            val screenOk = state.sessionReady && state.screenServiceReady
            CuaRow(state, update, partnerUsing, statusPage, divider = state.isMac || optionalRows(state, stream),
                onInstall = { installCommand = vm.cuaInstallCommand() }, onUpdate = { confirmUpdate = true },
                onCancelWaiting = vm::cancelWaitingUpdate)
            if (state.isMac) {
                val accountState = when {
                    state.macAccount != null -> CheckState.DONE
                    screenOk && state.cuaReady -> CheckState.TODO
                    else -> CheckState.LATER
                }
                ChecklistRow(accountState,
                    if (state.macAccount != null) stringResource(R.string.im_computer_check_mac_account_done, state.macAccount)
                    else stringResource(R.string.im_computer_check_mac_account),
                    if (state.macAccount == null) stringResource(R.string.im_computer_check_mac_account_help) else null) {
                    if (state.macAccount == null) Button(enabled = !state.busy, onClick = { askMacAccount = true }) {
                        Text(stringResource(R.string.im_computer_fill_in))
                    }
                }
                val grants = state.permissions
                ChecklistRow(when {
                    grants?.complete == true -> CheckState.DONE
                    state.macAccount != null && state.cuaReady && state.task == ComputerSetupTask.CHECKING -> CheckState.WAITING
                    state.macAccount != null && state.cuaReady -> CheckState.TODO
                    else -> CheckState.LATER
                }, stringResource(if (grants?.complete == true) R.string.im_computer_check_grants_done else R.string.im_computer_check_grants),
                    if (grants?.complete == true) null else stringResource(R.string.im_computer_check_grants_help),
                    divider = optionalRows(state, stream)) {
                    if (grants?.complete != true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = !state.busy, onClick = vm::requestMacPermissions) { Text(stringResource(R.string.im_computer_request_on_mac)) }
                        state.hostId?.let { hostId ->
                            TextButton(onClick = { nav.navigate(Screen.ComputerScreen(hostId)) }) { Text(stringResource(R.string.im_computer_operate_from_phone)) }
                        }
                    }
                }
            }
            OptionalRows(vm, state, stream, enablingStream = enablingStream, onEnableStream = { enablingStream = true })
        }
        if (!state.ready) SetupNote(HugeIcons.InformationCircle, stringResource(R.string.im_computer_auto_recheck))
    }
    if (enablingStream && stream != null) {
        RemoteStreamEnableFlow(stream, onStart = vm::startSunshine, onEnforce = vm::enforceStreamEncryption, onPair = vm::pairStream,
            onCancelPairing = vm::cancelStreamPairing, onDismissInterrupt = { vm.dismissStreamInterrupt() },
            onEnable = vm::enableStream, onFinished = { enablingStream = false })
    }
    installCommand?.let { command ->
        AlertDialog(onDismissRequest = { installCommand = null },
            title = { Text(stringResource(R.string.im_computer_install_confirm)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.im_computer_install_disclosure))
                    SetupDisclosure(stringResource(R.string.im_computer_show_command)) { RemoteScreenCopyCommand("", command) }
                }
            }, confirmButton = { TextButton(enabled = !state.busy && command == vm.cuaInstallCommand(), onClick = {
                installCommand = null
                vm.installCuaDriver()
            }) { Text(stringResource(R.string.im_computer_install)) } },
            dismissButton = { TextButton(onClick = { installCommand = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
    if (confirmUpdate) CuaUpdateDialog(state, vm.cuaInstallCommand(), partnerUsing, onDismiss = { confirmUpdate = false }) {
        confirmUpdate = false
        vm.updateCuaDriver()
    }
    if (askMacAccount) MacAccountDialog(state.username, onDismiss = { askMacAccount = false }) { user, password ->
        askMacAccount = false
        vm.saveMacAccount(user, password)
    }
}

/** Whether rows follow the required ones (so the last required row keeps its divider). */
private fun optionalRows(state: ComputerSetupState, stream: RemoteStreamSetupState?): Boolean {
    val probe = state.probe ?: return false
    val streamRow = stream?.status?.installed == true && (probe.os == "linux" || probe.os == "macos")
    val clipboardRow = probe.os == "linux" && !probe.usesRdp && probe.clipboard.isNullOrBlank()
    return streamRow || clipboardRow
}

/** Seeing the screen: a signed-in desktop and a screen service, each with the fix that fits this computer. */
@Composable
private fun ScreenRow(vm: ComputerSetupVM, state: ComputerSetupState, stream: RemoteStreamSetupState?,
                      enablingStream: Boolean, onEnableStream: () -> Unit) {
    val probe = state.probe ?: return
    val locked = probe.session.lockState == "locked"
    val done = state.sessionReady && state.screenServiceReady && !locked
    ChecklistRow(if (done) CheckState.DONE else CheckState.TODO,
        stringResource(when {
            done -> R.string.im_computer_check_screen_done
            !state.sessionReady -> R.string.im_computer_check_sign_in_desktop
            locked -> R.string.im_computer_check_unlock
            state.isMac -> R.string.im_computer_check_mac_sharing
            else -> R.string.im_computer_check_screen
        }),
        when {
            done -> null
            !state.sessionReady -> stringResource(R.string.im_computer_check_sign_in_desktop_help)
            locked -> stringResource(R.string.im_computer_check_unlock_help)
            state.isMac -> stringResource(R.string.im_computer_mac_sharing_help)
            state.isWindows -> stringResource(R.string.im_computer_check_windows_screen_help)
            else -> stringResource(R.string.im_computer_check_screen_help)
        }) {
        if (done || !state.sessionReady || locked) return@ChecklistRow
        when {
            state.isMac -> Button(enabled = !state.busy, onClick = vm::openMacSharingSettings) {
                Text(stringResource(R.string.im_computer_mac_open_sharing))
            }
            state.isWindows -> stream?.let {
                SunshineControls(it, macOS = false, required = true, busy = state.busy || enablingStream, onEnable = onEnableStream,
                    onRecheck = vm::checkStream, onOpenSettings = vm::openSunshineSettings)
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            probe.usesRdp -> SetupDisclosure(stringResource(R.string.im_computer_how_to)) { RemoteScreenRdpInstallGuidance(probe.session.desktop) }
            else -> SetupDisclosure(stringResource(R.string.im_computer_how_to)) { RemoteScreenVncInstallGuidance(probe.session.type, probe.session.desktop) }
        }
    }
}

/** Nice to have, never required: smoother picture through Sunshine, typing Chinese through a clipboard tool. */
@Composable
private fun OptionalRows(vm: ComputerSetupVM, state: ComputerSetupState, stream: RemoteStreamSetupState?,
                         enablingStream: Boolean, onEnableStream: () -> Unit) {
    val probe = state.probe ?: return
    val clipboardRow = probe.os == "linux" && !probe.usesRdp && probe.clipboard.isNullOrBlank()
    if (stream?.status?.installed == true && (probe.os == "linux" || probe.os == "macos")) {
        val on = stream.streamReady && stream.enabled == true
        ChecklistRow(if (on) CheckState.DONE else CheckState.OPTIONAL,
            stringResource(if (on) R.string.im_computer_check_hd_done else R.string.im_computer_check_hd),
            if (on) null else stringResource(R.string.im_computer_check_hd_help), divider = clipboardRow) {
            if (!on) SunshineControls(stream, macOS = probe.os == "macos", required = false, busy = state.busy || enablingStream,
                onEnable = onEnableStream, onRecheck = vm::checkStream, onOpenSettings = vm::openSunshineSettings)
        }
    }
    if (clipboardRow) {
        val tool = when (probe.session.type) { "wayland" -> "wl-clipboard"; "x11" -> "xclip"; else -> null }
        ChecklistRow(CheckState.OPTIONAL, stringResource(R.string.im_computer_check_chinese), stringResource(R.string.im_computer_check_chinese_help),
            divider = false) {
            if (tool != null) SetupDisclosure(stringResource(R.string.im_computer_how_to)) {
                RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $tool")
                RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $tool")
            }
        }
    }
}

/** Sunshine's state as a button (set up / turn on), its phase while working, and the Mac grants it needs. */
@Composable
private fun SunshineControls(
    stream: RemoteStreamSetupState,
    macOS: Boolean,
    required: Boolean,
    busy: Boolean,
    onEnable: () -> Unit,
    onRecheck: () -> Unit,
    onOpenSettings: (RemoteSunshinePermission) -> Unit,
) {
    val resources = LocalResources.current
    val status = stream.status
    when {
        stream.phase == RemoteStreamSetupPhase.CHECKING -> Text(stringResource(R.string.workspace_screen_stream_checking),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        stream.phase == RemoteStreamSetupPhase.ENFORCING -> Text(stringResource(R.string.workspace_screen_stream_enforcing),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        stream.phase == RemoteStreamSetupPhase.STARTING -> Text(stringResource(R.string.workspace_screen_stream_starting),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        status == null -> {
            stream.error?.let { Text(remoteScreenSetupError(resources, it), style = MaterialTheme.typography.bodyMedium) }
            TextButton(enabled = !busy, onClick = onRecheck) { Text(stringResource(R.string.im_computer_recheck)) }
        }
        !status.installed -> if (required) Text(stringResource(R.string.im_computer_sunshine_needed), style = MaterialTheme.typography.bodyMedium)
        else -> Button(enabled = !busy && !stream.busy, onClick = onEnable) {
            Text(stringResource(if (required) R.string.im_computer_sunshine_set_up else R.string.im_computer_stream_enable))
        }
    }
    // A Mac needs Sunshine's grants switched on at the computer; offer the page where that happens.
    if (macOS && status?.installed == true && !stream.busy) {
        RemoteSunshinePermissions(status, stream, enabled = !busy, onOpen = onOpenSettings)
    }
    stream.pairingError?.let { Text(streamPairingErrorText(it), color = MaterialTheme.colorScheme.error) }
    if (stream.enforcementError != null || stream.enforcement?.success == false) {
        Text(stringResource(R.string.workspace_screen_stream_enforce_failed), color = MaterialTheme.colorScheme.error)
    }
}

/** The Mac account Screen Sharing signs in with, when the setup did not sign in with a password. */
@Composable
private fun MacAccountDialog(initialUser: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var user by remember { mutableStateOf(initialUser) }
    // Credentials never enter saved instance state.
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.im_computer_mac_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.im_computer_mac_account_help))
                SetupGroup {
                    SetupField(stringResource(R.string.im_computer_account), user, { user = it }, divider = true)
                    SetupField(stringResource(R.string.im_computer_password), password, { password = it }, password = true)
                }
            }
        },
        confirmButton = { TextButton(enabled = user.isNotBlank() && password.isNotEmpty(), onClick = { onSave(user.trim(), password) }) {
            Text(stringResource(R.string.im_computer_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
