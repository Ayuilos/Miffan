package me.ayuilos.miffan.ui.im.computer

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.remote.NearbyComputer
import me.ayuilos.miffan.data.remote.NearbyComputerDiscovery
import me.ayuilos.miffan.data.remote.NearbyNetwork
import me.ayuilos.miffan.data.remote.NearbyScan
import me.ayuilos.miffan.data.remote.NearbySystem
import me.ayuilos.miffan.ui.components.ui.permission.PermissionLocalNetwork
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Wifi01
import org.koin.compose.koinInject

/**
 * Finding the computer, the way phones find a speaker or a printer: what is on this Wi-Fi shows up by
 * itself; computers reached through Tailscale are announced separately and listed on their own
 * page; typing an address is the fallback at the bottom.
 */
@Composable
internal fun ComputerFindStep(vm: ComputerSetupVM, state: ComputerSetupState, hosts: List<RemoteHostEntity>) {
    // Android 17 asks before an app may reach devices on the local network.
    val permission = if (Build.VERSION.SDK_INT >= 37) rememberPermissionState(PermissionLocalNetwork) else null
    permission?.let { PermissionManager(permissionState = it) }
    val allowed = permission?.allRequiredPermissionsGranted ?: true
    LaunchedEffect(permission) { if (permission != null && !permission.allRequiredPermissionsGranted) permission.requestPermissions() }

    val discovery: NearbyComputerDiscovery = koinInject()
    val scan by remember(discovery, allowed) { if (allowed) discovery.observe() else kotlinx.coroutines.flow.flowOf(NearbyScan(emptyList(), false)) }
        .collectAsStateWithLifecycle(NearbyScan(emptyList(), searching = true))
    var tailnet by rememberSaveable { mutableStateOf(false) }
    BackHandler(tailnet) { tailnet = false }

    val local = scan.computers.filter { it.network == NearbyNetwork.LOCAL }
    val remote = scan.computers.filter { it.network == NearbyNetwork.TAILSCALE }
    val pick: (NearbyComputer) -> Unit = vm::pickNearby

    if (tailnet) {
        SetupScreen(
            title = stringResource(R.string.im_computer_tailnet_title),
            subtitle = stringResource(R.string.im_computer_tailnet_help),
            state = state, onDismissError = vm::dismissError,
            primary = null,
            secondary = SetupAction(stringResource(R.string.im_computer_tailnet_back)) { tailnet = false },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.offset(y = (-4).dp)) {
                Icon(TailscaleLogo, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.im_computer_via_tailscale), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (remote.isEmpty()) SearchStatus(scan.searching, 0, emptyText = stringResource(R.string.im_computer_tailnet_none))
            remote.forEach { computer -> NearbyCard(computer, hosts, enabled = !state.busy) { pick(computer) } }
            SetupNote(HugeIcons.InformationCircle, stringResource(R.string.im_computer_tailnet_note))
        }
        return
    }

    SetupScreen(
        title = stringResource(R.string.im_computer_find_title),
        subtitle = stringResource(R.string.im_computer_find_help),
        state = state, onDismissError = vm::dismissError,
        primary = null,
    ) {
        if (hosts.isNotEmpty()) {
            SectionLabel(stringResource(R.string.im_computer_saved_computers))
            hosts.forEach { host ->
                ComputerCard(host.name, stringResource(R.string.im_computer_saved_subtitle),
                    host.system().icon(),
                    enabled = !state.busy) { vm.chooseHost(host.id, fromList = true) }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (!allowed) {
            SetupCard {
                Text(stringResource(R.string.im_computer_local_network_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.im_computer_local_network_help), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { permission?.requestPermissions() }) { Text(stringResource(R.string.im_computer_local_network_allow)) }
            }
        } else {
            SearchStatus(scan.searching, local.size, emptyText = stringResource(R.string.im_computer_nearby_none))
            local.forEach { computer -> NearbyCard(computer, hosts, enabled = !state.busy) { pick(computer) } }
            if (scan.tailscaleActive && remote.isNotEmpty()) TailscaleNotice(remote.size) { tailnet = true }
        }
        Spacer(Modifier.height(8.dp))
        SetupCard(spacing = 12.dp) {
            Text(stringResource(R.string.im_computer_not_found_title), style = MaterialTheme.typography.titleMedium)
            Tip("Mac", stringResource(R.string.im_computer_tip_mac))
            Tip("Windows", stringResource(R.string.im_computer_tip_windows))
            Tip("Linux", stringResource(R.string.im_computer_tip_linux))
            FilledTonalButton(onClick = vm::addManually, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.im_computer_add_by_address))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchStatus(searching: Boolean, found: Int, emptyText: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (searching) SearchingPulse()
        else Icon(HugeIcons.Wifi01, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when {
            searching && found == 0 -> stringResource(R.string.im_computer_searching)
            searching -> stringResource(R.string.im_computer_searching_found, found)
            found == 0 -> emptyText
            else -> stringResource(R.string.im_computer_nearby_found, found)
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NearbyCard(computer: NearbyComputer, hosts: List<RemoteHostEntity>, enabled: Boolean, onClick: () -> Unit) {
    val saved = hosts.any { it.host == computer.host && it.port == computer.port }
    val kind = computer.system.label(computer.detail)
    ComputerCard(computer.name ?: computer.host,
        if (saved) stringResource(R.string.im_computer_nearby_saved, kind) else kind,
        computer.system.icon(), enabled = enabled, onClick = onClick)
}

@Composable
private fun TailscaleNotice(count: Int, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center) {
                Icon(TailscaleLogo, "Tailscale", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.im_computer_tailnet_notice, count), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.im_computer_tailnet_notice_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Button(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.im_computer_tailnet_open))
            }
        }
    }
}

@Composable
private fun Tip(system: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(system, Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
