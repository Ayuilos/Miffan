package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.rerere.workspace.RemotePlatform

/** Adds [publicKey] to ~/.ssh/authorized_keys unless it is there already; runs in sh, bash, zsh and fish. */
internal fun authorizeKeyCommand(publicKey: String): String {
    val quoted = "'" + publicKey.trim().replace("'", "'\\''") + "'"
    return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && " +
        "grep -qxF $quoted ~/.ssh/authorized_keys || printf '\\n%s\\n' $quoted >> ~/.ssh/authorized_keys"
}

/** What the user runs by hand: [authorizeKeyCommand], then the account name to fill in. */
internal fun computerPublicKeyCommand(publicKey: String): String =
    authorizeKeyCommand(publicKey) + " && echo 'Miffan OK, user:' && whoami"

/** An SSH target as pasted from a terminal or an SSH config; null parts were not given. */
internal data class SshTarget(val host: String, val username: String? = null, val port: Int? = null)

/** ssh options that take a value, so the value is not mistaken for the destination. */
private const val SSH_OPTIONS_WITH_VALUE = "BbcDEeFIiJLlmOoPpQRSWw"

/**
 * Reads "user@host", "user@host:2222", "host:2222", "ssh://user@host:2222" and
 * "ssh -p 2222 user@host" (also "-l user"). Null when [input] is just a host, or not a target.
 */
internal fun parseSshTarget(input: String): SshTarget? {
    var text = input.trim()
    var username: String? = null
    var port: Int? = null
    val words = text.split(Regex("\\s+"))
    if (words.size > 1) {
        if (words.first() != "ssh") return null
        var destination: String? = null
        var index = 1
        while (index < words.size) {
            val word = words[index]
            val value = words.getOrNull(index + 1)
            when {
                word.length == 2 && word[0] == '-' && word[1] in SSH_OPTIONS_WITH_VALUE -> {
                    when (word[1]) {
                        'p' -> port = value?.toIntOrNull()
                        'l' -> username = value
                    }
                    index++
                }
                word.startsWith("-p") && word.length > 2 -> port = word.drop(2).toIntOrNull()
                word.startsWith("-") -> Unit
                destination == null -> destination = word
            }
            index++
        }
        text = destination ?: return null
    }
    text = text.removePrefix("ssh://").trimEnd('/')
    if (text.contains('@')) {
        username = text.substringBeforeLast('@').ifBlank { null }
        text = text.substringAfterLast('@')
    }
    val host = when {
        text.startsWith("[") -> text.substringAfter('[').substringBefore(']').also {
            text.substringAfter(']', "").removePrefix(":").toIntOrNull()?.let { port = it }
        }
        text.count { it == ':' } == 1 -> text.substringBefore(':').also { text.substringAfter(':').toIntOrNull()?.let { port = it } }
        else -> text
    }
    if (host.isBlank() || (username == null && port == null)) return null
    return SshTarget(host, username, port?.takeIf { it in 1..65535 })
}

/** The name a computer gets when the user leaves it empty: "macbook" for macbook.local, an IP as is. */
internal fun defaultComputerName(address: String): String {
    val host = address.trim()
    val numeric = host.contains(':') || host.split('.').all { it.toIntOrNull() != null }
    return if (numeric) host else host.substringBefore('.')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComputerAddressStep(
    vm: ComputerSetupVM,
    state: ComputerSetupState,
    keys: List<SshKeyEntity>,
    /** Editing [host]'s connection instead of adding a computer. */
    edit: Boolean = false,
    host: RemoteHostEntity? = null,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    // A password is what people have at hand; the setup turns it into Miffan's key right away.
    var passwordLogin by rememberSaveable { mutableStateOf(true) }
    var windows by rememberSaveable { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyRequested by rememberSaveable { mutableStateOf(false) }
    // Editing keeps the saved sign-in unless the user asks to change it.
    var changeSignIn by rememberSaveable { mutableStateOf(!edit) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(host?.id) {
        if (edit && host != null && !prefilled) {
            name = host.name; address = host.host; port = host.port.toString(); username = host.username
            keyId = host.sshKeyId
            windows = host.platform == RemotePlatform.WINDOWS.storageName
            prefilled = true
        }
    }
    // Credentials never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var keyMenu by remember { mutableStateOf(false) }
    val selectedKey = keys.find { it.id == keyId } ?: keys.firstOrNull()
    // Windows needs the setup script anyway, and the script authorizes a key: no password option there.
    val usePassword = passwordLogin && !windows
    // submitAddress has already saved this computer even when fingerprint reading fails.
    val retryFingerprint = state.hostId != null && !edit
    val editable = !state.busy && !retryFingerprint
    // Every way in ends with Miffan's key (password sign-in authorizes it), so make one up front.
    LaunchedEffect(keys.isEmpty(), changeSignIn) {
        if (changeSignIn && keys.isEmpty() && !keyRequested) {
            keyRequested = true
            vm.createAppKey(reuseExisting = true) { keyId = it.id }
        }
    }
    // A pasted "user@host:port" counts right away; the fields are tidied once the address loses focus.
    val target = parseSshTarget(address)
    val finalAddress = target?.host ?: address.trim()
    val finalUsername = target?.username ?: username.trim()
    val finalPort = target?.port?.toString() ?: port
    fun tidyAddress() {
        val parsed = parseSshTarget(address) ?: return
        address = parsed.host
        parsed.username?.let { username = it }
        parsed.port?.let { port = it.toString() }
    }
    val valid = finalAddress.isNotBlank() && finalUsername.isNotBlank() && finalPort.toIntOrNull() in 1..65535 &&
        (!changeSignIn || if (usePassword) password.isNotEmpty() && selectedKey != null else selectedKey != null) && (!edit || prefilled)
    ComputerSetupStepLayout(
        title = stringResource(if (edit) R.string.im_computer_edit_title else R.string.im_computer_address_title),
        supporting = stringResource(if (edit) R.string.im_computer_edit_help else R.string.im_computer_address_help),
        state = state, onDismissError = vm::dismissError,
        primary = if (retryFingerprint) SetupAction(stringResource(R.string.im_computer_reread_fingerprint), onClick = vm::rereadFingerprint)
        else SetupAction(stringResource(if (edit) R.string.im_computer_save_continue else R.string.im_computer_continue), enabled = valid) {
            tidyAddress()
            vm.submitAddress(name.trim().ifEmpty { defaultComputerName(finalAddress) }, finalAddress, finalPort.toInt(), finalUsername, when {
                !changeSignIn -> null
                usePassword -> ComputerSetupAuth.Password(password, keyId = selectedKey?.id)
                else -> ComputerSetupAuth.AppKey(requireNotNull(selectedKey).id)
            }, windows)
        },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !windows, enabled = editable, onClick = { windows = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.im_computer_system_unix)) }
            SegmentedButton(selected = windows, enabled = editable, onClick = { windows = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.im_computer_system_windows)) }
        }
        // On Windows the script comes first: it prints the address and username to fill in.
        val keyPicker: @Composable () -> Unit = {
            if (selectedKey != null && keys.size > 1) Box {
                TextButton(enabled = editable, onClick = { keyMenu = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.im_computer_other_key, selectedKey.name))
                }
                DropdownMenu(keyMenu, onDismissRequest = { keyMenu = false }) {
                    keys.forEach { key ->
                        DropdownMenuItem(text = { Text(key.name) }, onClick = { keyId = key.id; keyMenu = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.im_computer_generate_key)) },
                        onClick = { keyMenu = false; vm.createAppKey { keyId = it.id } })
                }
            }
        }
        if (windows && changeSignIn) WindowsSetupCard(selectedKey, keyPicker)
        SetupCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.im_computer_address)) },
                    supportingText = { Text(stringResource(R.string.im_computer_address_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    enabled = editable, singleLine = true,
                    modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) tidyAddress() })
                OutlinedTextField(finalPort, { if (it.all(Char::isDigit) && it.length <= 5) { tidyAddress(); port = it } },
                    label = { Text(stringResource(R.string.im_computer_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    enabled = editable, singleLine = true, modifier = Modifier.width(88.dp))
            }
            OutlinedTextField(finalUsername.takeIf { target?.username != null } ?: username, { tidyAddress(); username = it },
                label = { Text(stringResource(R.string.im_computer_username)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.im_computer_name_optional)) },
                placeholder = finalAddress.takeIf { it.isNotBlank() }?.let { { Text(defaultComputerName(it)) } },
                enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (!changeSignIn) SetupCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.im_computer_sign_in), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.im_computer_sign_in_keep), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = editable, onClick = { changeSignIn = true }) { Text(stringResource(R.string.im_computer_sign_in_change)) }
            }
        } else if (!windows) SetupCard {
            Text(stringResource(R.string.im_computer_sign_in), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = passwordLogin, enabled = editable, onClick = { passwordLogin = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.im_computer_password_login)) }
                SegmentedButton(selected = !passwordLogin, enabled = editable, onClick = { passwordLogin = false; password = "" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.im_computer_key_command)) }
            }
            if (passwordLogin) {
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.im_computer_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.im_computer_password_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (selectedKey != null) {
                Text(stringResource(R.string.im_computer_key_command_help), style = MaterialTheme.typography.bodyMedium)
                val command = computerPublicKeyCommand(selectedKey.publicKey)
                RemoteScreenCopyCommand("", command)
                SendToComputerButton("Miffan SSH key", command)
                keyPicker()
            }
        }
    }
}
