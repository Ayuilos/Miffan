package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand

internal fun computerPublicKeyCommand(publicKey: String): String {
    val quoted = "'" + publicKey.trim().replace("'", "'\\''") + "'"
    return "mkdir -p ~/.ssh && printf '\\n%s\\n' $quoted >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
}

@Composable
internal fun ComputerAddressStep(vm: ComputerSetupVM, state: ComputerSetupState, keys: List<SshKeyEntity>) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var passwordLogin by rememberSaveable { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf<String?>(null) }
    // Credentials never enter saved instance state.
    var password by remember { mutableStateOf("") }
    var keyMenu by remember { mutableStateOf(false) }
    val selectedKey = keys.find { it.id == keyId } ?: keys.firstOrNull()
    // submitAddress has already saved this computer even when fingerprint reading fails.
    val retryFingerprint = state.hostId != null
    val enabled = !state.busy && !retryFingerprint
    Text(stringResource(R.string.im_computer_address_title), style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.im_computer_name)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.im_computer_address)) },
        supportingText = { Text(stringResource(R.string.im_computer_address_hint)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { if (it.all(Char::isDigit)) port = it }, label = { Text(stringResource(R.string.im_computer_port)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.im_computer_username)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    FilterChip(selected = !passwordLogin, onClick = { passwordLogin = false; password = "" }, enabled = enabled,
        label = { Text(stringResource(R.string.im_computer_app_key)) })
    FilterChip(selected = passwordLogin, onClick = { passwordLogin = true }, enabled = enabled,
        label = { Text(stringResource(R.string.im_computer_password_login)) })
    if (passwordLogin) OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.im_computer_password)) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    else {
        if (selectedKey != null) {
            Box {
                OutlinedButton(onClick = { keyMenu = true }, enabled = enabled) { Text(selectedKey.name) }
                DropdownMenu(keyMenu, onDismissRequest = { keyMenu = false }) {
                    keys.forEach { key -> DropdownMenuItem(text = { Text(key.name) }, enabled = enabled,
                        onClick = { keyId = key.id; keyMenu = false }) }
                }
            }
            Text(stringResource(R.string.im_computer_key_command_help))
            RemoteScreenCopyCommand(selectedKey.name, selectedKey.publicKey)
            RemoteScreenCopyCommand(stringResource(R.string.im_computer_authorize_key), computerPublicKeyCommand(selectedKey.publicKey))
        }
        TextButton(enabled = enabled, onClick = { vm.createAppKey { keyId = it.id } }) { Text(stringResource(R.string.im_computer_generate_key)) }
    }
    ComputerSetupButton(stringResource(if (retryFingerprint) R.string.im_computer_reread_fingerprint else R.string.im_computer_continue), state.busy,
        enabled = retryFingerprint || (name.isNotBlank() && address.isNotBlank() && username.isNotBlank() && port.toIntOrNull() in 1..65535 &&
            if (passwordLogin) password.isNotEmpty() else selectedKey != null),
        onClick = {
            if (retryFingerprint) vm.rereadFingerprint()
            else vm.submitAddress(name.trim(), address.trim(), port.toInt(), username.trim(),
                if (passwordLogin) ComputerSetupAuth.Password(password) else ComputerSetupAuth.AppKey(requireNotNull(selectedKey).id))
        })
}
