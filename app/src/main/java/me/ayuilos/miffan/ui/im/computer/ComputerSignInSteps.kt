package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.remote.NearbySystem
import me.ayuilos.miffan.data.repository.WindowsSetupScript
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tick01

/** The computer at the top of a sign-in screen: its mark in a large tile and its name. */
@Composable
private fun ComputerHero(icon: ImageVector, name: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeviceTile(icon, size = 72.dp)
        name?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Account and password, nothing else: Miffan turns the password into its own key right away. */
@Composable
internal fun ComputerSignInStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val target = state.target
    var username by rememberSaveable(target?.host) { mutableStateOf(state.username) }
    // Credentials never enter saved instance state.
    var password by remember(target?.host) { mutableStateOf("") }
    SetupScreen(
        title = stringResource(R.string.im_computer_sign_in_title),
        subtitle = stringResource(if (state.isMac) R.string.im_computer_sign_in_help_mac else R.string.im_computer_sign_in_help),
        state = state, onDismissError = vm::dismissError, centered = true,
        hero = { ComputerHero((target?.system ?: NearbySystem.UNKNOWN).icon(), target?.name) },
        primary = SetupAction(stringResource(R.string.im_computer_connect_action), enabled = username.isNotBlank() && password.isNotEmpty()) {
            vm.signIn(username.trim(), password)
        },
        secondary = SetupAction(stringResource(R.string.im_computer_other_sign_in)) {
            vm.setUsername(username.trim())
            vm.useKey()
        },
    ) {
        SetupGroup {
            SetupField(stringResource(R.string.im_computer_account), username, { username = it }, enabled = !state.busy,
                placeholder = stringResource(R.string.im_computer_account_placeholder), divider = true)
            SetupField(stringResource(R.string.im_computer_password), password, { password = it }, enabled = !state.busy, password = true)
        }
        SetupNote(HugeIcons.LockKey, stringResource(if (state.isMac) R.string.im_computer_password_note_mac else R.string.im_computer_password_note))
    }
}

/**
 * Sign in with Miffan's key: one command on the computer authorizes it. Also where a computer that
 * does not take passwords lands. Coming back to the app after handing the command over tries again
 * by itself, a few times at most.
 */
@Composable
internal fun ComputerKeyStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val key by vm.appKey.collectAsState()
    LaunchedEffect(Unit) { vm.ensureAppKey() }
    var username by rememberSaveable(state.target?.host) { mutableStateOf(state.username) }
    var handedOver by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(handedOver, username) {
        if (handedOver && username.isNotBlank()) vm.signInWithKey(username.trim(), quiet = true)
        onPauseOrDispose { }
    }
    val name = state.target?.name.orEmpty()
    SetupScreen(
        title = stringResource(if (state.passwordRefused) R.string.im_computer_key_only_title else R.string.im_computer_key_title),
        subtitle = if (state.passwordRefused) stringResource(R.string.im_computer_key_only_help, name)
            else stringResource(R.string.im_computer_key_help),
        state = state, onDismissError = vm::dismissError, centered = true,
        hero = { KeyHero() },
        primary = SetupAction(stringResource(R.string.im_computer_key_done), enabled = username.isNotBlank() && key != null) {
            vm.signInWithKey(username.trim())
        },
        secondary = SetupAction(stringResource(R.string.im_computer_find_another), onClick = vm::findAnother),
    ) {
        SetupCard(spacing = 18.dp) {
            SetupInstruction(1, stringResource(R.string.im_computer_key_step_terminal))
            SetupInstruction(2, stringResource(R.string.im_computer_key_step_paste)) {
                key?.let { k ->
                    val command = computerPublicKeyCommand(k.publicKey)
                    CommandPreview(command)
                    HandOverButtons("Miffan SSH key", command) { handedOver = true }
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SetupInstruction(3, stringResource(R.string.im_computer_key_step_account)) {
                SetupGroup(Modifier.fillMaxWidth(), nested = true) {
                    SetupField(stringResource(R.string.im_computer_account), username, { username = it }, enabled = !state.busy,
                        placeholder = stringResource(R.string.im_computer_account_placeholder))
                }
            }
        }
        KeyStatus(state.keyRefused)
    }
}

/** A Windows computer runs the setup script once: it opens Windows' own remote login and allows Miffan's key. */
@Composable
internal fun ComputerWindowsStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val context = LocalContext.current
    val key by vm.appKey.collectAsState()
    LaunchedEffect(Unit) { vm.ensureAppKey() }
    val script by produceState<String?>(null, key?.publicKey) {
        val publicKey = key?.publicKey ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { WindowsSetupScript.fromAssets(context.assets, publicKey) }.getOrNull() }
    }
    var username by rememberSaveable(state.target?.host) { mutableStateOf(state.username) }
    var address by rememberSaveable { mutableStateOf("") }
    var handedOver by rememberSaveable { mutableStateOf(false) }
    val needsAddress = state.target == null
    val canSignIn = username.isNotBlank() && (!needsAddress || address.isNotBlank()) && key != null
    LifecycleResumeEffect(handedOver, username, address) {
        if (handedOver && canSignIn && !needsAddress) vm.signInWithKey(username.trim(), quiet = true)
        onPauseOrDispose { }
    }
    SetupScreen(
        title = stringResource(R.string.im_computer_windows_title),
        subtitle = stringResource(R.string.im_computer_windows_help),
        state = state, onDismissError = vm::dismissError, centered = true,
        hero = { ComputerHero(WindowsLogo, state.target?.name) },
        primary = SetupAction(stringResource(R.string.im_computer_key_done), enabled = canSignIn) {
            if (needsAddress) vm.setWindowsAddress(normalizeAddressInput(address).trim())
            vm.signInWithKey(username.trim())
        },
        secondary = SetupAction(stringResource(R.string.im_computer_find_another), onClick = vm::findAnother),
    ) {
        SetupCard(spacing = 18.dp) {
            SetupInstruction(1, stringResource(R.string.im_computer_windows_step_admin))
            SetupInstruction(2, stringResource(R.string.im_computer_windows_step_paste)) {
                script?.let {
                    CommandPreview(it.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty())
                    HandOverButtons("Miffan Windows setup", it) { handedOver = true }
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SetupInstruction(3, stringResource(if (needsAddress) R.string.im_computer_windows_step_fill_both else R.string.im_computer_windows_step_fill)) {
                SetupGroup(Modifier.fillMaxWidth(), nested = true) {
                    if (needsAddress) SetupField(stringResource(R.string.im_computer_address), address, { address = it }, enabled = !state.busy,
                        keyboardType = KeyboardType.Uri, divider = true)
                    SetupField(stringResource(R.string.im_computer_account), username, { username = it }, enabled = !state.busy)
                }
            }
        }
        KeyStatus(state.keyRefused)
    }
}

@Composable
private fun KeyHero() {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center) {
        Icon(HugeIcons.Key01, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun KeyStatus(refused: Boolean) {
    if (refused) SetupNote(HugeIcons.Alert02, stringResource(R.string.im_computer_key_refused))
    else SetupNote(HugeIcons.InformationCircle, stringResource(R.string.im_computer_key_auto))
}

/** Copy, or send to an app the user also has on the computer: the phone's clipboard rarely reaches it. */
@Composable
private fun HandOverButtons(subject: String, text: String, onHandedOver: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(subject, text))
            copied = true
            onHandedOver()
        }) {
            Icon(if (copied) HugeIcons.Tick01 else HugeIcons.Copy01, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (copied) R.string.im_computer_windows_script_copied else R.string.im_computer_copy))
        }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
            onHandedOver()
        }) {
            Icon(HugeIcons.Share08, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.im_computer_windows_send_script))
        }
    }
}

/** Typing an address, the fallback when nothing shows up nearby. */
@Composable
internal fun ComputerManualStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf(state.username) }
    var port by rememberSaveable { mutableStateOf("22") }
    var name by rememberSaveable { mutableStateOf("") }
    // Read through normalizeAddressInput, never rewritten while typing: that would break the IME's composition.
    val typed = normalizeAddressInput(address)
    val parsed = parseSshTarget(typed)
    val finalAddress = parsed?.host ?: typed.trim()
    val finalUser = parsed?.username ?: username.trim()
    val finalPort = (parsed?.port?.toString() ?: port).toIntOrNull()
    val valid = finalAddress.isNotBlank() && finalPort in 1..65535
    SetupScreen(
        title = stringResource(R.string.im_computer_manual_title),
        subtitle = stringResource(R.string.im_computer_manual_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_next), enabled = valid) {
            vm.submitManual(finalAddress, finalPort!!, finalUser, name.trim())
        },
    ) {
        SetupGroup {
            SetupField(stringResource(R.string.im_computer_address), address, { address = it }, enabled = !state.busy,
                placeholder = stringResource(R.string.im_computer_address_placeholder), keyboardType = KeyboardType.Uri, divider = true)
            SetupField(stringResource(R.string.im_computer_account), parsed?.username ?: username, { username = it }, enabled = !state.busy,
                placeholder = stringResource(R.string.im_computer_account_optional))
        }
        SetupDisclosure(stringResource(R.string.im_computer_more_settings)) {
            SetupGroup {
                SetupField(stringResource(R.string.im_computer_port), parsed?.port?.toString() ?: port,
                    { if (it.all(Char::isDigit) && it.length <= 5) port = it }, enabled = !state.busy, keyboardType = KeyboardType.Number, divider = true)
                SetupField(stringResource(R.string.im_computer_name), name, { name = it }, enabled = !state.busy,
                    placeholder = finalAddress.takeIf { it.isNotBlank() }?.let(::defaultComputerName))
            }
        }
        if (state.unreachable) SetupCard(spacing = 10.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(HugeIcons.Alert02, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.im_computer_unreachable, finalAddress), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.im_computer_unreachable_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::continueAsWindows, enabled = !state.busy) {
                Icon(WindowsLogo, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.im_computer_is_windows))
            }
        }
    }
}

/** A saved computer answered with another identity: never trusted without the user saying so. */
@Composable
internal fun ComputerVerifyStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    SetupScreen(
        title = stringResource(R.string.im_computer_identity_changed_title),
        subtitle = stringResource(R.string.im_computer_identity_changed),
        state = state, onDismissError = vm::dismissError, centered = true,
        hero = {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center) {
                Icon(HugeIcons.Alert02, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        primary = SetupAction(stringResource(R.string.im_computer_trust_connect), enabled = state.hostKey != null, onClick = vm::trustAndConnect),
        secondary = SetupAction(stringResource(R.string.im_computer_dont_connect)) { vm.back() },
    ) {
        state.hostKey?.let { key ->
            SetupCard(spacing = 6.dp) {
                Text(key.algorithm, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(key.sha256Fingerprint, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
