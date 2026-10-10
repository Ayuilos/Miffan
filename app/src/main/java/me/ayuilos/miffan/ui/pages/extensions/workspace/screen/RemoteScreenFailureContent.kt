package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteRdpCertificateChangedException
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException
import me.ayuilos.miffan.data.repository.RemoteStreamCertificateChangedException

@Composable
internal fun RemoteScreenFailureContent(
    state: RemoteScreenUiState,
    settingsAvailable: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    failureText: String? = null,
    settingsText: String? = null,
    /** Pins a changed RDP or Sunshine certificate after the user confirmed it; null hides the action. */
    onTrustCertificate: ((String) -> Unit)? = null,
    /** Switches a fixed endpoint nothing listens on to automatic connection; null hides the action. */
    onUseAutomatic: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    val error = (state as? RemoteScreenUiState.Failed)?.error
    val unavailable = error as? RemoteScreenUnavailableException
    // Problems fixed on the computer itself: the user acts there, then retries here.
    val retryOnly = error is RemoteStreamCertificateChangedException || unavailable?.problem in setOf(RemoteScreenProblem.NO_GRAPHICAL_SESSION, RemoteScreenProblem.VNC_START_FAILED,
        RemoteScreenProblem.RDP_START_FAILED, RemoteScreenProblem.RDP_ALREADY_CONFIGURED, RemoteScreenProblem.RDP_KEYRING_LOCKED,
        RemoteScreenProblem.RDP_CREDENTIAL_SETUP_UNAVAILABLE, RemoteScreenProblem.RDP_CERTIFICATE_CHANGED)
    val missingServer = unavailable?.problem == RemoteScreenProblem.NO_VNC_SERVER || unavailable?.problem == RemoteScreenProblem.NO_RDP_SERVER
    val changedCertificate = when (error) {
        is RemoteRdpCertificateChangedException -> error.actualSha256?.let { CertificateChange(error.expectedSha256, it) }
        is RemoteStreamCertificateChangedException -> error.actualSha256?.let { CertificateChange(error.expectedSha256, it) }
        else -> null
    }
    var confirmTrust by remember(error) { mutableStateOf(false) }
    // Unspecified unavailable problems keep P1's settings action; ordinary failures still retry.
    val settingsOnly = unavailable != null && !retryOnly && !missingServer
    Surface(modifier.padding(24.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failureText ?: when {
                missingServer -> resources.getString(R.string.workspace_screen_vnc_missing)
                error != null -> remoteScreenSetupError(resources, error)
                else -> resources.getString(R.string.workspace_screen_closed)
            })
            when (unavailable?.problem) {
                RemoteScreenProblem.NO_VNC_SERVER -> RemoteScreenVncInstallGuidance(unavailable.detail, unavailable.desktop)
                RemoteScreenProblem.NO_RDP_SERVER -> RemoteScreenRdpInstallGuidance(unavailable.desktop)
                else -> Unit
            }
            if (unavailable?.problem == RemoteScreenProblem.VNC_START_FAILED || unavailable?.problem == RemoteScreenProblem.RDP_START_FAILED) {
                unavailable.detail?.takeIf { it.isNotBlank() }?.let { RemoteScreenLog(it) }
            }
            Row {
                if (onUseAutomatic != null) {
                    TextButton(onClick = onUseAutomatic) { Text(stringResource(R.string.workspace_screen_use_automatic)) }
                }
                if (changedCertificate != null && onTrustCertificate != null) {
                    TextButton(onClick = { confirmTrust = true }) { Text(stringResource(R.string.workspace_screen_rdp_trust)) }
                }
                if (!settingsOnly) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(if (error != null) R.string.workspace_screen_retry else R.string.workspace_screen_reconnect))
                    }
                }
                if (settingsOnly || missingServer) {
                    TextButton(enabled = settingsAvailable, onClick = onSettings) {
                        Text(settingsText ?: stringResource(R.string.workspace_screen_settings))
                    }
                }
            }
        }
    }
    if (confirmTrust && changedCertificate != null && onTrustCertificate != null) {
        val actual = changedCertificate.actual
        AlertDialog(onDismissRequest = { confirmTrust = false },
            title = { Text(stringResource(R.string.workspace_screen_rdp_trust_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.workspace_screen_rdp_trust_hint))
                    FingerprintBlock(stringResource(R.string.workspace_screen_rdp_fingerprint_before), changedCertificate.expected)
                    FingerprintBlock(stringResource(R.string.workspace_screen_rdp_fingerprint_now), actual)
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmTrust = false; onTrustCertificate(actual) }) {
                    Text(stringResource(R.string.workspace_screen_rdp_trust))
                }
            },
            dismissButton = { TextButton(onClick = { confirmTrust = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private data class CertificateChange(val expected: String, val actual: String)

/** SHA-256 hex as four aligned rows of four groups, so two fingerprints can be compared row by row. */
@Composable
private fun FingerprintBlock(label: String, sha256: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sha256.replace(":", "").uppercase().chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}
