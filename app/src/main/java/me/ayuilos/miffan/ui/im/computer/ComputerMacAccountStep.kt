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
import kotlinx.coroutines.CancellationException
import me.ayuilos.miffan.R

@Composable
internal fun ComputerMacAccountStep(vm: ComputerSetupVM, state: ComputerSetupState, sshUsername: String) {
    var username by rememberSaveable(state.hostId) { mutableStateOf(sshUsername) }
    var password by remember(state.hostId) { mutableStateOf("") }
    var saved by remember(state.hostId) { mutableStateOf<String?>(null) }
    var loading by remember(state.hostId) { mutableStateOf(true) }
    var loadFailed by remember(state.hostId) { mutableStateOf(false) }
    var useSaved by remember(state.hostId) { mutableStateOf(false) }
    LaunchedEffect(vm, state.hostId) {
        try {
            saved = vm.savedMacAccount()
            useSaved = saved != null
            saved?.let { username = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
        finally { loading = false }
    }
    Text(stringResource(R.string.im_computer_mac_account), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.im_computer_mac_account_help))
    if (loading) CircularProgressIndicator()
    if (loadFailed) Text(stringResource(R.string.im_computer_saved_account_failed), color = MaterialTheme.colorScheme.error)
    if (saved != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = useSaved, enabled = !state.busy && !loading, onCheckedChange = {
            useSaved = it
            password = ""
            if (it) username = saved.orEmpty()
        })
        Text(stringResource(R.string.im_computer_use_saved_account))
    }
    OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.im_computer_username)) },
        enabled = !state.busy && !loading && !useSaved, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!useSaved) OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.im_computer_password)) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = !state.busy && !loading, singleLine = true, modifier = Modifier.fillMaxWidth())
    ComputerSetupButton(stringResource(R.string.im_computer_continue), state.busy,
        enabled = !loading && username.isNotBlank() && (useSaved || password.isNotEmpty()),
        onClick = { vm.saveMacAccount(username.trim(), if (useSaved) null else password) })
}
