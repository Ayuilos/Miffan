package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand

internal fun computerPublicKeyCommand(publicKey: String): String {
    val quoted = "'" + publicKey.trim().replace("'", "'\\''") + "'"
    return "mkdir -p ~/.ssh && printf '\\n%s\\n' $quoted >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
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
    var passwordLogin by rememberSaveable { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf<String?>(null) }
    var keyRequested by rememberSaveable { mutableStateOf(false) }
    // Editing keeps the saved sign-in unless the user asks to change it.
    var changeSignIn by rememberSaveable { mutableStateOf(!edit) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(host?.id) {
        if (edit && host != null && !prefilled) {
            name = host.name; address = host.host; port = host.port.toString(); username = host.username
            keyId = host.sshKeyId
            prefilled = true
        }
    }
    // Credentials never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var keyMenu by remember { mutableStateOf(false) }
    val selectedKey = keys.find { it.id == keyId } ?: keys.firstOrNull()
    // submitAddress has already saved this computer even when fingerprint reading fails.
    val retryFingerprint = state.hostId != null && !edit
    val editable = !state.busy && !retryFingerprint
    // The recommended path needs a key; make one rather than asking the user to.
    LaunchedEffect(passwordLogin, keys.isEmpty(), changeSignIn) {
        if (changeSignIn && !passwordLogin && keys.isEmpty() && !keyRequested) {
            keyRequested = true
            vm.createAppKey(reuseExisting = true) { keyId = it.id }
        }
    }
    val valid = name.isNotBlank() && address.isNotBlank() && username.isNotBlank() && port.toIntOrNull() in 1..65535 &&
        (!changeSignIn || if (passwordLogin) password.isNotEmpty() else selectedKey != null) && (!edit || prefilled)
    ComputerSetupStepLayout(
        title = stringResource(if (edit) R.string.im_computer_edit_title else R.string.im_computer_address_title),
        supporting = stringResource(if (edit) R.string.im_computer_edit_help else R.string.im_computer_address_help),
        state = state, onDismissError = vm::dismissError,
        primary = if (retryFingerprint) SetupAction(stringResource(R.string.im_computer_reread_fingerprint), onClick = vm::rereadFingerprint)
        else SetupAction(stringResource(if (edit) R.string.im_computer_save_continue else R.string.im_computer_continue), enabled = valid) {
            vm.submitAddress(name.trim(), address.trim(), port.toInt(), username.trim(), when {
                !changeSignIn -> null
                passwordLogin -> ComputerSetupAuth.Password(password)
                else -> ComputerSetupAuth.AppKey(requireNotNull(selectedKey).id)
            })
        },
    ) {
        SetupCard {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.im_computer_name)) },
                enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.im_computer_address)) },
                    supportingText = { Text(stringResource(R.string.im_computer_address_hint)) },
                    enabled = editable, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(port, { if (it.all(Char::isDigit) && it.length <= 5) port = it },
                    label = { Text(stringResource(R.string.im_computer_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = editable, singleLine = true, modifier = Modifier.width(88.dp))
            }
            OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.im_computer_username)) },
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
        } else SetupCard {
            Text(stringResource(R.string.im_computer_sign_in), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !passwordLogin, enabled = editable, onClick = { passwordLogin = false; password = "" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.im_computer_app_key)) }
                SegmentedButton(selected = passwordLogin, enabled = editable, onClick = { passwordLogin = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.im_computer_password_login)) }
            }
            if (passwordLogin) {
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.im_computer_password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else if (selectedKey != null) {
                Text(stringResource(R.string.im_computer_key_command_help), style = MaterialTheme.typography.bodyMedium)
                RemoteScreenCopyCommand("", computerPublicKeyCommand(selectedKey.publicKey))
                if (keys.size > 1) Box {
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
        }
    }
}
