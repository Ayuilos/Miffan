package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM

@Composable
fun RemoteHostScreenSettingsDialog(
    host: RemoteHostEntity,
    vm: WorkspaceVM,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val resources = LocalResources.current
    var loaded by remember(host.id) { mutableStateOf(false) }
    var busy by remember(host.id) { mutableStateOf(false) }
    var active by remember(host.id) { mutableStateOf(true) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    var enabled by remember(host.id) { mutableStateOf(false) }
    var unix by remember(host.id) { mutableStateOf(false) }
    var port by remember(host.id) { mutableStateOf("5900") }
    var path by remember(host.id) { mutableStateOf("") }
    var auth by remember(host.id) { mutableStateOf(RemoteScreenAuth.NONE) }
    var username by remember(host.id) { mutableStateOf("") }
    // Credentials deliberately stay out of rememberSaveable / Android saved state.
    var password by remember(host.id) { mutableStateOf("") }
    var hasPassword by remember(host.id) { mutableStateOf(false) }
    DisposableEffect(host.id) { onDispose { active = false } }
    fun load() {
        error = null
        busy = true
        vm.getScreenConfig(host.id) { result ->
            if (!active) return@getScreenConfig
            busy = false
            result.fold(onSuccess = { config ->
                if (config == null) {
                    error = resources.getString(R.string.workspace_screen_host_missing)
                } else {
                    enabled = config.enabled
                    unix = config.endpoint is RemoteScreenEndpoint.Unix
                    port = (config.endpoint as? RemoteScreenEndpoint.Tcp)?.port?.toString() ?: "5900"
                    path = (config.endpoint as? RemoteScreenEndpoint.Unix)?.path.orEmpty()
                    auth = config.auth
                    username = config.username
                    hasPassword = config.hasPassword
                    loaded = true
                }
            }, onFailure = { error = it.localizedMessage ?: resources.getString(R.string.workspace_screen_save_failed) })
        }
    }
    LaunchedEffect(host.id) { load() }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val validPath = path.startsWith('/') && path.none { it == '\u0000' || it == '\n' }
    val valid = loaded && (if (unix) validPath else validPort != null) &&
        (auth == RemoteScreenAuth.NONE || hasPassword || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_screen_settings)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(host.name, style = MaterialTheme.typography.titleSmall)
                if (busy && !loaded) CircularProgressIndicator()
                if (loaded) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.workspace_screen_enabled), Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                    }
                    Text(stringResource(R.string.workspace_screen_endpoint), style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.selectableGroup()) {
                        ScreenSettingOption(!unix, R.string.workspace_screen_tcp, !busy) { unix = false }
                        ScreenSettingOption(unix, R.string.workspace_screen_unix, !busy) { unix = true }
                    }
                    if (unix) {
                        OutlinedTextField(path, { path = it; error = null },
                            label = { Text(stringResource(R.string.workspace_screen_socket_path)) },
                            supportingText = { Text(stringResource(R.string.workspace_screen_path_hint)) },
                            isError = path.isNotEmpty() && !validPath,
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(port, { port = it; error = null },
                            label = { Text(stringResource(R.string.workspace_screen_port)) },
                            supportingText = { Text(stringResource(R.string.workspace_screen_port_hint)) },
                            isError = validPort == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.workspace_screen_auth), style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.selectableGroup()) {
                        RemoteScreenAuth.entries.forEach { option ->
                            ScreenSettingOption(auth == option, when (option) {
                                RemoteScreenAuth.NONE -> R.string.workspace_screen_auth_none
                                RemoteScreenAuth.VNC_PASSWORD -> R.string.workspace_screen_auth_vnc
                                RemoteScreenAuth.MACOS_ACCOUNT -> R.string.workspace_screen_auth_macos
                            }, !busy) { auth = option; password = ""; error = null }
                        }
                    }
                    if (auth == RemoteScreenAuth.MACOS_ACCOUNT) {
                        OutlinedTextField(username, { username = it; error = null },
                            label = { Text(stringResource(R.string.workspace_screen_username)) },
                            placeholder = { Text(host.username) },
                            supportingText = { Text(stringResource(R.string.workspace_screen_username_hint)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                    if (auth != RemoteScreenAuth.NONE) {
                        OutlinedTextField(password, { password = it; error = null },
                            label = { Text(stringResource(R.string.workspace_screen_password)) },
                            placeholder = if (hasPassword) ({ Text(stringResource(R.string.workspace_screen_password_saved)) }) else null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                    Text(stringResource(R.string.workspace_screen_setup_help), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded && !busy) TextButton(onClick = ::load) { Text(stringResource(R.string.workspace_screen_retry)) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                error = null
                val endpoint = if (unix) RemoteScreenEndpoint.Unix(path) else RemoteScreenEndpoint.Tcp(requireNotNull(validPort))
                vm.updateScreenConfig(host.id, enabled, endpoint, auth, username, password.takeIf { it.isNotEmpty() }) { result ->
                    if (!active) return@updateScreenConfig
                    busy = false
                    result.fold(onSuccess = { saved ->
                        if (saved) { onSaved(); onDismiss() }
                        else error = resources.getString(R.string.workspace_screen_host_missing)
                    }, onFailure = { error = it.localizedMessage ?: resources.getString(R.string.workspace_screen_save_failed) })
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ScreenSettingOption(selected: Boolean, label: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, null, enabled = enabled)
        Text(stringResource(label))
    }
}
