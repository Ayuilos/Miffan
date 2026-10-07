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
    // Credentials never enter saved instance state.
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
    val editable = !state.busy && !loading
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_mac_account),
        supporting = stringResource(R.string.im_computer_mac_account_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_continue),
            enabled = !loading && username.isNotBlank() && (useSaved || password.isNotEmpty())) {
            vm.saveMacAccount(username.trim(), if (useSaved) null else password)
        },
    ) {
        if (loadFailed) SetupWarning(stringResource(R.string.im_computer_saved_account_failed))
        SetupCard {
            saved?.let { account ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.im_computer_use_saved_account))
                        Text(account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = useSaved, enabled = editable, onCheckedChange = {
                        useSaved = it
                        password = ""
                        if (it) username = account
                    })
                }
            }
            if (!useSaved) {
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.im_computer_username)) },
                    enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.im_computer_password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
