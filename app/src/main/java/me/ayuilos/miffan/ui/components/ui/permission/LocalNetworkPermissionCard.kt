package me.ayuilos.miffan.ui.components.ui.permission

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Wifi01

/**
 * The local network permission, or null below Android 17, where every app may reach the local network.
 *
 * Ported from RikkaHub 903e058b9: with targetSdk 37 the app cannot reach anything on the local
 * network without it, so computers, self-hosted gateways and LAN model servers only time out.
 */
@Composable
fun rememberLocalNetworkPermissionState(): PermissionState? =
    if (Build.VERSION.SDK_INT >= 37) rememberPermissionState(PermissionLocalNetwork) else null

/**
 * A card asking for the local network permission; a tap requests it.
 *
 * The caller decides whether to show it from [permissionState], adding the list item only when it
 * is needed so no empty gap is left behind.
 */
@Composable
fun LocalNetworkPermissionCard(
    permissionState: PermissionState,
    modifier: Modifier = Modifier,
) {
    PermissionManager(permissionState = permissionState)

    Surface(
        onClick = { permissionState.requestPermissions() },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Wifi01,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_local_network),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.permission_local_network_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
