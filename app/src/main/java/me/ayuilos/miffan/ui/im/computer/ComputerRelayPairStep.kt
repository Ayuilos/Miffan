package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.relay.OFFICIAL_RELAY_URL
import me.ayuilos.miffan.data.repository.relay.RelaySetupScript
import me.ayuilos.miffan.data.repository.relay.manualConnectorCommands
import me.ayuilos.miffan.data.repository.relay.parseRelayPairingInput
import me.ayuilos.miffan.data.repository.relay.parseSshHostFingerprint
import me.ayuilos.miffan.ui.components.ui.QRCodeScannerDialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.QrCode01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.workspace.relay.RelayEndpoint

/**
 * Sets up and pairs a Mac or Linux computer over a relay without an initial direct SSH connection:
 * supports either the one-line POSIX setup script (`miffan-pair://v1?...` with out-of-band host key
 * fingerprints) or manual `miffan-connector` CLI pairing using this phone's `devicePub` and SSH key.
 */
@Composable
internal fun ComputerRelayPairStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val language = LocalConfiguration.current.locales[0].language
    val appKey by vm.appKey.collectAsStateWithLifecycle()
    val relayPhone by vm.relayPhone.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.ensureRelayPairReady() }

    var pairingInput by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf(state.username) }
    var computerName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var accessCode by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    var scanningPair by remember { mutableStateOf(false) }
    var scanningRelay by remember { mutableStateOf(false) }

    val parsed = remember(pairingInput) { parseRelayPairingInput(pairingInput) }
    val scriptEndpoint = remember(customUrl, accessCode) {
        val url = customUrl.trim().ifEmpty { OFFICIAL_RELAY_URL }
        runCatching { RelayEndpoint(url, accessCode.trim().takeIf { it.isNotEmpty() }) }
            .getOrElse { RelayEndpoint(OFFICIAL_RELAY_URL, accessCode.trim().takeIf { it.isNotEmpty() }) }
    }
    val setupScript by produceState<String?>(initialValue = null, appKey?.publicKey, relayPhone, scriptEndpoint) {
        val sshPub = appKey?.publicKey
        val phone = relayPhone
        value = if (sshPub == null || phone == null) null else withContext(Dispatchers.IO) {
            runCatching {
                RelaySetupScript.fromAssets(context.assets, sshPub, phone, scriptEndpoint)
            }.getOrNull()
        }
    }
    val manualCommands = remember(relayPhone, scriptEndpoint) {
        relayPhone?.let { manualConnectorCommands(it, scriptEndpoint) }
    }

    val resolvedUser = username.trim().ifBlank { parsed?.username.orEmpty() }
    val fpValid = fingerprint.isBlank() || parseSshHostFingerprint(fingerprint) != null
    val urlValid = customUrl.isBlank() || runCatching { RelayEndpoint(customUrl.trim()) }.isSuccess
    val canConnect = parsed != null && resolvedUser.isNotEmpty() && fpValid && urlValid

    SetupScreen(
        title = stringResource(R.string.im_computer_relay_pair_title),
        subtitle = stringResource(R.string.im_computer_relay_pair_subtitle),
        state = state,
        onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_connect_action), enabled = canConnect) {
            vm.connectViaRelay(pairingInput, username, computerName, customUrl, accessCode, fingerprint)
        },
    ) {
        SetupCard(spacing = 14.dp) {
            SetupInstruction(1, stringResource(R.string.im_computer_relay_pair_step_run)) {
                setupScript?.let { script ->
                    CommandPreview(script)
                    HandOverButtons(stringResource(R.string.im_computer_relay_pair_title), script) {}
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SetupInstruction(2, stringResource(R.string.im_computer_relay_pair_step_paste)) {
                SetupGroup(Modifier.fillMaxWidth(), nested = true) {
                    SetupField(
                        label = stringResource(R.string.im_computer_relay_pair_code_label),
                        value = pairingInput,
                        onValueChange = { pairingInput = it },
                        enabled = !state.busy,
                        placeholder = stringResource(R.string.im_computer_relay_pair_code_placeholder),
                        keyboardType = KeyboardType.Uri,
                        divider = parsed?.username == null,
                    )
                    if (parsed?.username == null) {
                        SetupField(
                            label = stringResource(R.string.im_computer_account),
                            value = username,
                            onValueChange = { username = it },
                            enabled = !state.busy,
                            placeholder = stringResource(R.string.im_computer_account_placeholder),
                        )
                    }
                }
                parsed?.username?.let { detectedUser ->
                    Text(
                        stringResource(R.string.im_computer_relay_pair_user_detected, detectedUser),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { scanningPair = true },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Icon(HugeIcons.QrCode01, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.im_computer_relay_pair_scan))
                }
            }
        }

        if (state.keyRefused) KeyStatus(refused = true)

        SetupDisclosure(
            title = stringResource(R.string.im_computer_away_own_relay),
            initiallyExpanded = customUrl.isNotEmpty() || accessCode.isNotEmpty(),
        ) {
            SetupCard(padding = 16.dp, spacing = 0.dp) {
                SetupField(
                    label = stringResource(R.string.im_computer_away_relay_address),
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    enabled = !state.busy,
                    placeholder = "https://relay.example.com",
                    keyboardType = KeyboardType.Uri,
                    divider = true,
                )
                SetupField(
                    label = stringResource(R.string.im_computer_away_access_code),
                    value = accessCode,
                    onValueChange = { accessCode = it },
                    enabled = !state.busy,
                    placeholder = stringResource(R.string.im_computer_away_access_code_hint),
                    password = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scanningRelay = true },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Icon(HugeIcons.QrCode01, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.im_computer_away_scan))
                }
                TextButton(
                    onClick = { uriHandler.openUri(relayHostingGuideUrl(language)) },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(stringResource(R.string.im_computer_away_how_to_host))
                }
            }
        }

        SetupDisclosure(stringResource(R.string.im_computer_relay_pair_manual_title)) {
            SetupCard(spacing = 12.dp) {
                Text(
                    stringResource(R.string.im_computer_relay_pair_manual_ssh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relayPhone?.let { phone ->
                    CopyableKeyRow(
                        label = stringResource(R.string.im_computer_relay_pair_device_pub),
                        value = phone.devicePublicKeyB64u,
                        detail = "deviceId: ${phone.deviceId}",
                    )
                }
                appKey?.let { key ->
                    CopyableKeyRow(
                        label = stringResource(R.string.im_computer_relay_pair_ssh_pub),
                        value = key.publicKey,
                    )
                }
                manualCommands?.let { cmds ->
                    CopyableKeyRow(
                        label = stringResource(R.string.im_computer_relay_pair_manual_commands),
                        value = cmds,
                    )
                }
            }
            SetupGroup {
                SetupField(
                    label = stringResource(R.string.im_computer_relay_pair_fingerprint),
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    enabled = !state.busy,
                    placeholder = stringResource(R.string.im_computer_relay_pair_fingerprint_hint),
                    divider = true,
                )
                SetupField(
                    label = stringResource(R.string.im_computer_name),
                    value = computerName,
                    onValueChange = { computerName = it },
                    enabled = !state.busy,
                    placeholder = parsed?.name ?: stringResource(R.string.im_computer_relay_pair_name_optional),
                )
            }
        }
    }

    if (scanningPair) QRCodeScannerDialog(
        onResult = { text ->
            pairingInput = text
            scanningPair = false
        },
        onPermissionDenied = { scanningPair = false },
        onDismissRequest = { scanningPair = false },
    )
    if (scanningRelay) QRCodeScannerDialog(
        onResult = { text ->
            parseRelayCode(text)?.let { (scannedUrl, scannedCode) ->
                customUrl = scannedUrl
                accessCode = scannedCode.orEmpty()
            }
            scanningRelay = false
        },
        onPermissionDenied = { scanningRelay = false },
        onDismissRequest = { scanningRelay = false },
    )
}

@Composable
private fun CopyableKeyRow(label: String, value: String, detail: String? = null) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
                    copied = true
                }) {
                    Icon(
                        if (copied) HugeIcons.Tick01 else HugeIcons.Copy01,
                        contentDescription = stringResource(if (copied) R.string.copied else R.string.im_computer_copy),
                        modifier = Modifier.size(18.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
