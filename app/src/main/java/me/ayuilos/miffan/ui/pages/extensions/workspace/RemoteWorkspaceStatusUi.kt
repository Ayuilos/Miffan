package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.res.Resources
import me.ayuilos.miffan.R
import java.text.DateFormat
import java.util.Date
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.RemoteConfigurationState
import me.ayuilos.miffan.data.repository.RemoteConnectionActivity
import me.ayuilos.miffan.data.repository.RemoteConnectionStatus
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteOperationOutcome
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.rerere.workspace.WorkspaceShellStatus

/** Live connection state comes from the session owner, never a historical test result. */
internal fun remoteWorkspaceStatusLabel(workspaceStrings: Resources,
    workspace: WorkspaceEntity,
    host: RemoteHostRuntimeState?,
    runtime: RemoteWorkspaceRuntimeState?,
    connection: RemoteConnectionStatus? = null,
): String {
    when (connection) {
        RemoteConnectionStatus.CONNECTED -> return workspaceStrings.getString(R.string.workspace_connection_connected)
        RemoteConnectionStatus.CONNECTING -> return workspaceStrings.getString(R.string.workspace_connecting_remote_server)
        RemoteConnectionStatus.RECONNECTING -> return workspaceStrings.getString(R.string.workspace_connection_reconnecting)
        RemoteConnectionStatus.FAILED -> return workspaceStrings.getString(R.string.workspace_connection_failed_retry)
        RemoteConnectionStatus.DISCONNECTED -> return workspaceStrings.getString(R.string.workspace_disconnected)
        null -> Unit
    }
    val activity = runtime?.activity
    if (activity == RemoteConnectionActivity.CONNECTING) return workspaceStrings.getString(R.string.workspace_connecting_remote_server)
    if (activity == RemoteConnectionActivity.OPERATING) return workspaceStrings.getString(R.string.workspace_remote_operation_running)
    when (host?.configuration) {
        RemoteConfigurationState.HOST_KEY_UNTRUSTED -> return workspaceStrings.getString(R.string.workspace_host_fingerprint_pending)
        RemoteConfigurationState.CREDENTIAL_MISSING -> return workspaceStrings.getString(R.string.workspace_credentials_missing)
        RemoteConfigurationState.INVALID -> return workspaceStrings.getString(R.string.workspace_host_config_check)
        else -> Unit
    }
    val directoryCheck = runtime?.lastDirectoryCheck
    val hostCheck = host?.lastConnection
    if (hostCheck?.success == false &&
        (directoryCheck == null || hostCheck.timestampMillis >= directoryCheck.timestampMillis)
    ) {
        return workspaceStrings.getString(R.string.workspace_host_connection_failed_at, hostCheck.timestampMillis.shortDateTime(workspaceStrings))
    }
    directoryCheck?.let { check ->
        return if (check.success) workspaceStrings.getString(R.string.workspace_directory_check_passed_at, check.timestampMillis.shortDateTime(workspaceStrings))
        else workspaceStrings.getString(R.string.workspace_directory_check_failed_at, check.timestampMillis.shortDateTime(workspaceStrings))
    }
    hostCheck?.let { check ->
        return if (check.success) workspaceStrings.getString(R.string.workspace_host_connection_passed_at, check.timestampMillis.shortDateTime(workspaceStrings))
        else workspaceStrings.getString(R.string.workspace_host_connection_failed_at, check.timestampMillis.shortDateTime(workspaceStrings))
    }
    return when (workspace.shellStatus) {
        WorkspaceShellStatus.READY.name -> workspaceStrings.getString(R.string.workspace_configured_not_checked)
        WorkspaceShellStatus.BROKEN.name -> workspaceStrings.getString(R.string.workspace_connection_directory_recheck)
        else -> workspaceStrings.getString(R.string.workspace_connection_not_checked)
    }
}

internal fun remoteWorkspaceLastOperationLabel(workspaceStrings: Resources, runtime: RemoteWorkspaceRuntimeState?): String? =
    runtime?.lastOperation?.let { operation ->
        val result = when (operation.outcome) {
            RemoteOperationOutcome.SUCCESS -> workspaceStrings.getString(R.string.workspace_operation_success)
            RemoteOperationOutcome.COMMAND_FAILED -> workspaceStrings.getString(R.string.workspace_command_failed)
            RemoteOperationOutcome.FILE_FAILED -> workspaceStrings.getString(R.string.workspace_file_operation_failed)
            RemoteOperationOutcome.OUTCOME_UNKNOWN -> workspaceStrings.getString(R.string.workspace_outcome_unknown)
        }
        workspaceStrings.getString(R.string.workspace_last_operation_at, result, operation.timestampMillis.shortDateTime(workspaceStrings))
    }

internal fun remoteHostStatusLabel(workspaceStrings: Resources, host: RemoteHostEntity, runtime: RemoteHostRuntimeState?): String {
    if (host.trustedHostKeySha256 == null || runtime?.configuration == RemoteConfigurationState.HOST_KEY_UNTRUSTED) {
        return workspaceStrings.getString(R.string.workspace_host_fingerprint_pending)
    }
    return when (runtime?.activity) {
        RemoteConnectionActivity.CONNECTING -> workspaceStrings.getString(R.string.workspace_connecting)
        RemoteConnectionActivity.OPERATING -> workspaceStrings.getString(R.string.workspace_operating)
        else -> when (runtime?.configuration) {
            RemoteConfigurationState.CREDENTIAL_MISSING -> workspaceStrings.getString(R.string.workspace_credentials_missing)
            RemoteConfigurationState.INVALID -> workspaceStrings.getString(R.string.workspace_host_config_check)
            else -> runtime?.lastConnection?.let {
                if (it.success) workspaceStrings.getString(R.string.workspace_check_passed_at, it.timestampMillis.shortDateTime(workspaceStrings))
                else workspaceStrings.getString(R.string.workspace_connection_failed_at, it.timestampMillis.shortDateTime(workspaceStrings))
            } ?: workspaceStrings.getString(R.string.workspace_on_demand_unchecked)
        }
    }
}

private fun Long.shortDateTime(workspaceStrings: Resources): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT, workspaceStrings.configuration.locales[0],
    ).format(Date(this))
