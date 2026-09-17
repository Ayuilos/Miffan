package me.ayuilos.miffan.ui.pages.extensions.workspace

import java.text.DateFormat
import java.util.Date
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.RemoteConfigurationState
import me.ayuilos.miffan.data.repository.RemoteConnectionActivity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteOperationOutcome
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.rerere.workspace.WorkspaceShellStatus

/** Remote SSH is connected only during an operation; a successful old test never means online. */
internal fun remoteWorkspaceStatusLabel(
    workspace: WorkspaceEntity,
    host: RemoteHostRuntimeState?,
    runtime: RemoteWorkspaceRuntimeState?,
): String {
    val activity = runtime?.activity
    if (activity == RemoteConnectionActivity.CONNECTING) return "正在连接远程服务器"
    if (activity == RemoteConnectionActivity.OPERATING) return "正在远程操作"
    when (host?.configuration) {
        RemoteConfigurationState.HOST_KEY_UNTRUSTED -> return "主机指纹待确认"
        RemoteConfigurationState.CREDENTIAL_MISSING -> return "登录凭据缺失"
        RemoteConfigurationState.INVALID -> return "主机配置需检查"
        else -> Unit
    }
    val directoryCheck = runtime?.lastDirectoryCheck
    val hostCheck = host?.lastConnection
    if (hostCheck?.success == false &&
        (directoryCheck == null || hostCheck.timestampMillis >= directoryCheck.timestampMillis)
    ) {
        return "主机上次连接失败 ${hostCheck.timestampMillis.shortDateTime()}"
    }
    directoryCheck?.let { check ->
        return if (check.success) "按需连接 · 目录上次检查通过 ${check.timestampMillis.shortDateTime()}"
        else "目录上次检查失败 ${check.timestampMillis.shortDateTime()}"
    }
    hostCheck?.let { check ->
        return if (check.success) "按需连接 · 主机上次连接通过 ${check.timestampMillis.shortDateTime()}"
        else "主机上次连接失败 ${check.timestampMillis.shortDateTime()}"
    }
    return when (workspace.shellStatus) {
        WorkspaceShellStatus.READY.name -> "已配置 · 按需连接（本次启动尚未检查）"
        WorkspaceShellStatus.BROKEN.name -> "连接或目录待重新检查"
        else -> "尚未检查连接"
    }
}

internal fun remoteWorkspaceLastOperationLabel(runtime: RemoteWorkspaceRuntimeState?): String? =
    runtime?.lastOperation?.let { operation ->
        val result = when (operation.outcome) {
            RemoteOperationOutcome.SUCCESS -> "成功"
            RemoteOperationOutcome.COMMAND_FAILED -> "命令失败"
            RemoteOperationOutcome.FILE_FAILED -> "文件操作失败"
            RemoteOperationOutcome.OUTCOME_UNKNOWN -> "结果未知；请核查远程状态"
        }
        "上次操作：$result · ${operation.timestampMillis.shortDateTime()}"
    }

internal fun remoteHostStatusLabel(host: RemoteHostEntity, runtime: RemoteHostRuntimeState?): String {
    if (host.trustedHostKeySha256 == null || runtime?.configuration == RemoteConfigurationState.HOST_KEY_UNTRUSTED) {
        return "主机指纹待确认"
    }
    return when (runtime?.activity) {
        RemoteConnectionActivity.CONNECTING -> "正在连接"
        RemoteConnectionActivity.OPERATING -> "正在操作"
        else -> when (runtime?.configuration) {
            RemoteConfigurationState.CREDENTIAL_MISSING -> "登录凭据缺失"
            RemoteConfigurationState.INVALID -> "主机配置需检查"
            else -> runtime?.lastConnection?.let {
                if (it.success) "按需连接 · 上次检查通过 ${it.timestampMillis.shortDateTime()}"
                else "上次连接失败 ${it.timestampMillis.shortDateTime()}"
            } ?: "按需连接 · 本次启动尚未检查"
        }
    }
}

private fun Long.shortDateTime(): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(this))
