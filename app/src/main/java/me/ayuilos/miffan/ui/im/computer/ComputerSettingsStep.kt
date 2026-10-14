package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteAddressNetwork
import me.ayuilos.miffan.data.db.entity.RemoteHostAddress
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.remoteAddressNetwork
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Wifi01

/**
 * A saved computer's connection settings: its name, every address it can be reached at (Miffan
 * picks whichever answers) and how Miffan signs in. Address changes apply at once; the name and
 * account are saved with the main action, which also checks the computer again.
 */
@Composable
internal fun ComputerSettingsStep(vm: ComputerSetupVM, state: ComputerSetupState, host: RemoteHostEntity?) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var newAddress by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(host?.id) {
        if (host != null && !prefilled) {
            name = host.name; username = host.username
            prefilled = true
        }
    }
    val valid = host != null && prefilled && username.isNotBlank()
    SetupScreen(
        title = null, subtitle = null,
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_save_continue), enabled = valid) {
            if (host != null) vm.saveConnection(name.trim(), host.host, host.port, username.trim())
        },
    ) {
        if (host == null) return@SetupScreen
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceTile(host.system().icon(), size = 72.dp)
            Text(host.system().label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SetupGroup {
            SetupField(stringResource(R.string.im_computer_name), name, { name = it }, enabled = !state.busy)
        }

        SectionLabel(stringResource(R.string.im_computer_connection_ways))
        SetupGroup {
            val addresses = host.addresses
            addresses.forEach { address ->
                AddressRow(address, lastUsed = address == host.lastAddress && addresses.size > 1,
                    removable = addresses.size > 1 && !state.busy, onRemove = { vm.removeAddress(address) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (adding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SetupField(stringResource(R.string.im_computer_address), newAddress, { newAddress = it }, enabled = !state.busy,
                            placeholder = stringResource(R.string.im_computer_address_placeholder), keyboardType = KeyboardType.Uri)
                    }
                    TextButton(onClick = {
                        vm.addAddress(newAddress)
                        newAddress = ""; adding = false
                    }, enabled = newAddress.isNotBlank() && !state.busy) { Text(stringResource(R.string.im_computer_add_address)) }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !state.busy) { adding = true },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Add01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(R.string.im_computer_add_address), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SetupNote(HugeIcons.InformationCircle, stringResource(R.string.im_computer_addresses_help))

        SectionLabel(stringResource(R.string.im_computer_sign_in_section))
        SetupGroup {
            SetupField(stringResource(R.string.im_computer_account), username, { username = it }, enabled = !state.busy,
                placeholder = stringResource(R.string.im_computer_account_placeholder), divider = true)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !state.busy, onClick = vm::changeSignIn),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.im_computer_sign_in_method), Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (host.sshKeyId != null) R.string.im_computer_method_key else R.string.im_computer_method_password),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.im_computer_sign_in_again), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One address: which network it is on, the address itself, and whether it was the last to answer. */
@Composable
private fun AddressRow(address: RemoteHostAddress, lastUsed: Boolean, removable: Boolean, onRemove: () -> Unit) {
    val network = remoteAddressNetwork(address.host)
    val (icon, label) = when (network) {
        RemoteAddressNetwork.LOCAL -> HugeIcons.Wifi01 to stringResource(R.string.im_computer_address_local)
        RemoteAddressNetwork.TAILSCALE -> TailscaleLogo to "Tailscale"
        RemoteAddressNetwork.OTHER -> HugeIcons.Globe02 to stringResource(R.string.im_computer_address_other)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NetworkTile(icon)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(if (address.port == 22) address.host else "${address.host}:${address.port}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (lastUsed) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(stringResource(R.string.im_computer_address_last_used), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (removable) IconButton(onClick = onRemove) {
            Icon(HugeIcons.Delete02, stringResource(R.string.im_computer_remove_address), Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetworkTile(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}
