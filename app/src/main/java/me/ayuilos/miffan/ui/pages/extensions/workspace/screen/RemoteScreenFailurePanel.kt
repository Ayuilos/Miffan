package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException

@Composable
internal fun RemoteScreenFailurePanel(
    state: RemoteScreenUiState,
    settingsAvailable: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val error = (state as? RemoteScreenUiState.Failed)?.error
    val unavailable = error as? RemoteScreenUnavailableException
    val retryOnly = unavailable?.problem in setOf(RemoteScreenProblem.NO_GRAPHICAL_SESSION, RemoteScreenProblem.VNC_START_FAILED)
    val missingServer = unavailable?.problem == RemoteScreenProblem.NO_VNC_SERVER
    // Unspecified unavailable problems keep P1's settings action; ordinary failures still retry.
    val settingsOnly = unavailable != null && !retryOnly && !missingServer
    Surface(modifier.padding(24.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                missingServer -> resources.getString(R.string.workspace_screen_vnc_missing)
                error != null -> remoteScreenSetupError(resources, error)
                else -> resources.getString(R.string.workspace_screen_closed)
            })
            if (missingServer) RemoteScreenVncInstallGuidance(unavailable.detail, unavailable.desktop)
            if (unavailable?.problem == RemoteScreenProblem.VNC_START_FAILED) {
                unavailable.detail?.takeIf { it.isNotBlank() }?.let { RemoteScreenLog(it) }
            }
            Row {
                if (!settingsOnly) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(if (error != null) R.string.workspace_screen_retry else R.string.workspace_screen_reconnect))
                    }
                }
                if (settingsOnly || missingServer) {
                    TextButton(enabled = settingsAvailable, onClick = onSettings) {
                        Text(stringResource(R.string.workspace_screen_settings))
                    }
                }
            }
        }
    }
}
