package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.ui.pages.extensions.workspace.WorkspaceVM

private enum class ScreenConnectionMode { AUTOMATIC, TCP, UNIX }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteHostScreenSettingsDialog(
    host: RemoteHostEntity,
    vm: WorkspaceVM,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val resources = LocalResources.current
    var loaded by remember(host.id) { mutableStateOf(false) }
    var configBusy by remember(host.id) { mutableStateOf(false) }
    val environments by vm.screenEnvironments.collectAsStateWithLifecycle()
    val environment = environments[host.id] ?: RemoteScreenEnvironmentState()
    val busy = configBusy || environment.busy
    var active by remember(host.id) { mutableStateOf(true) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    var enabled by remember(host.id) { mutableStateOf(false) }
    var mode by remember(host.id) { mutableStateOf(ScreenConnectionMode.AUTOMATIC) }
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
        configBusy = true
        vm.getScreenConfig(host.id) { result ->
            if (!active) return@getScreenConfig
            configBusy = false
            result.fold(onSuccess = { config ->
                if (config == null) {
                    error = resources.getString(R.string.workspace_screen_host_missing)
                } else {
                    enabled = config.enabled
                    mode = if (!config.enabled) ScreenConnectionMode.AUTOMATIC else when (config.endpoint) {
                        RemoteScreenEndpoint.Helper -> ScreenConnectionMode.AUTOMATIC
                        is RemoteScreenEndpoint.Tcp -> ScreenConnectionMode.TCP
                        is RemoteScreenEndpoint.Unix -> ScreenConnectionMode.UNIX
                    }
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
    val endpointValid = when (mode) {
        ScreenConnectionMode.AUTOMATIC -> true
        ScreenConnectionMode.TCP -> validPort != null
        ScreenConnectionMode.UNIX -> validPath
    }
    val valid = loaded && endpointValid &&
        (auth == RemoteScreenAuth.NONE || hasPassword || password.isNotEmpty())

    fun save() {
        configBusy = true
        error = null
        val endpoint = when (mode) {
            ScreenConnectionMode.AUTOMATIC -> RemoteScreenEndpoint.Helper
            ScreenConnectionMode.TCP -> RemoteScreenEndpoint.Tcp(requireNotNull(validPort))
            ScreenConnectionMode.UNIX -> RemoteScreenEndpoint.Unix(path)
        }
        vm.updateScreenConfig(host.id, enabled, endpoint, auth, username, password.takeIf { it.isNotEmpty() }) { result ->
            if (!active) return@updateScreenConfig
            configBusy = false
            result.fold(onSuccess = { saved ->
                if (saved) { onSaved(); onDismiss() }
                else error = resources.getString(R.string.workspace_screen_host_missing)
            }, onFailure = { error = it.localizedMessage ?: resources.getString(R.string.workspace_screen_save_failed) })
        }
    }

    // A full-screen page rather than an alert: it holds settings and the whole environment check.
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false,
            decorFitsSystemWindows = false),
    ) {
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.workspace_screen_settings))
                        Text(host.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(enabled = !busy, onClick = onDismiss) { Icon(HugeIcons.Cancel01, stringResource(R.string.common_cancel)) }
                },
                actions = {
                    TextButton(enabled = valid && !busy, onClick = ::save) { Text(stringResource(R.string.common_save)) }
                },
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (configBusy && !loaded) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                if (loaded) {
                    SettingsCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.workspace_screen_enabled), Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
                        }
                    }
                    SettingsCard {
                        Text(stringResource(R.string.workspace_screen_endpoint), style = MaterialTheme.typography.titleSmall)
                        Column(Modifier.selectableGroup()) {
                            ScreenSettingOption(mode == ScreenConnectionMode.AUTOMATIC, R.string.workspace_screen_automatic, !busy) { mode = ScreenConnectionMode.AUTOMATIC }
                            ScreenSettingOption(mode == ScreenConnectionMode.TCP, R.string.workspace_screen_tcp, !busy) { mode = ScreenConnectionMode.TCP }
                            ScreenSettingOption(mode == ScreenConnectionMode.UNIX, R.string.workspace_screen_unix, !busy) { mode = ScreenConnectionMode.UNIX }
                        }
                        when (mode) {
                            ScreenConnectionMode.UNIX -> OutlinedTextField(path, { path = it; error = null },
                                label = { Text(stringResource(R.string.workspace_screen_socket_path)) },
                                supportingText = { Text(stringResource(R.string.workspace_screen_path_hint)) },
                                isError = path.isNotEmpty() && !validPath,
                                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            ScreenConnectionMode.TCP -> OutlinedTextField(port, { port = it; error = null },
                                label = { Text(stringResource(R.string.workspace_screen_port)) },
                                supportingText = { Text(stringResource(R.string.workspace_screen_port_hint)) },
                                isError = validPort == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                            ScreenConnectionMode.AUTOMATIC -> Text(stringResource(R.string.workspace_screen_automatic_help),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SettingsCard {
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
                        Text(stringResource(R.string.workspace_screen_setup_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded && !busy) TextButton(onClick = ::load) { Text(stringResource(R.string.workspace_screen_retry)) }
                SettingsCard { RemoteScreenEnvironmentPanel(host, environment, vm, blocked = configBusy) }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
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
