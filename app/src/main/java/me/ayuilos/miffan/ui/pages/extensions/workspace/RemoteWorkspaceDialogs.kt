package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.ui.components.ai.workspaceKindIcon
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostKey

@Composable
internal fun RemoteHostCard(
    host: RemoteHostEntity,
    keyName: String?,
    runtime: RemoteHostRuntimeState? = null,
    onEdit: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val authentication = when {
        host.sshKeyId != null -> "SSH 密钥 · ${keyName ?: "密钥不可用"}"
        host.authType == "PRIVATE_KEY" -> "粘贴私钥"
        else -> "密码"
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onVerify)
            .padding(start = 0.dp, top = 16.dp, bottom = 16.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                workspaceKindIcon(true),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${host.username}@${host.host}:${host.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${remoteHostStatusLabel(host, runtime).replace("本次启动尚未检查", "待检查")} · $authentication",
                style = MaterialTheme.typography.labelSmall,
                color = if (host.trustedHostKeySha256 == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.size(48.dp)) {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxSize()) {
                Icon(HugeIcons.MoreVertical, contentDescription = "主机操作")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("编辑主机") }, onClick = { menuExpanded = false; onEdit() })
                DropdownMenuItem(text = { Text("确认指纹并测试") }, onClick = { menuExpanded = false; onVerify() })
                DropdownMenuItem(text = { Text("删除主机") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun RemoteHostEditorDialog(
    host: RemoteHostEntity?,
    sshKeys: List<SshKeyEntity>,
    keyMaterialStatus: Map<String, Boolean>,
    draft: HostEditorDraft,
    onDraftChange: (HostEditorDraft) -> Unit,
    onDismiss: () -> Unit,
    onManageKeys: () -> Unit,
    onCreate: (String, String, Int, String, RemoteAuthentication?, String?, (Result<RemoteHostEntity>) -> Unit) -> Unit,
    onUpdate: (String, String, String, Int, String, RemoteAuthentication?, String?, (Result<Boolean>) -> Unit) -> Unit,
) {
    val name = draft.name
    val hostname = draft.hostname
    val port = draft.port
    val username = draft.username
    val authMode = draft.authMode
    val selectedKeyId = draft.selectedKeyId ?: sshKeys.firstOrNull()?.id
    val credential = draft.credential
    val passphrase = draft.passphrase
    val originalAuthMode = HostEditorDraft.fromHost(host).authMode
    var error by remember(host?.id) { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var active by remember(host?.id) { mutableStateOf(true) }
    DisposableEffect(host?.id) { onDispose { active = false } }
    var keyMenu by remember { mutableStateOf(false) }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val changedAuthentication = host != null && authMode != originalAuthMode
    val authValid = when (authMode) {
        HostAuthMode.SAVED_KEY -> selectedKeyId != null && sshKeys.any { it.id == selectedKeyId } && keyMaterialStatus[selectedKeyId] == true
        HostAuthMode.PASSWORD, HostAuthMode.PASTED_KEY -> credential.isNotBlank() || (host != null && !changedAuthentication)
    }
    val valid = name.isNotBlank() && hostname.isNotBlank() && username.isNotBlank() && validPort != null &&
        authValid

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (host == null) "添加远程主机" else "编辑远程主机") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("通过 APP 内置 SSH 连接。Tailscale 地址或私网地址都可以填写在主机地址中。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { onDraftChange(draft.copy(name = it)); error = null }, label = { Text("显示名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hostname, { onDraftChange(draft.copy(hostname = it)); error = null }, label = { Text("主机地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { onDraftChange(draft.copy(port = it)); error = null }, label = { Text("SSH 端口") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = port.isNotBlank() && validPort == null)
                OutlinedTextField(username, { onDraftChange(draft.copy(username = it)); error = null }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RemoteHostAuthSelector(mode = authMode, onModeChange = {
                    if (it != authMode) {
                        onDraftChange(draft.copy(authMode = it, credential = "", passphrase = ""))
                        error = null
                    }
                })
                if (authMode == HostAuthMode.SAVED_KEY) {
                    val selectedKey = sshKeys.find { it.id == selectedKeyId }
                    OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedKey?.let { "${it.name} · ${it.fingerprint}" } ?: "选择 SSH 密钥", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        sshKeys.forEach { key ->
                            DropdownMenuItem(text = { Text(key.name) }, onClick = { onDraftChange(draft.copy(selectedKeyId = key.id)); keyMenu = false })
                        }
                    }
                    if (selectedKey != null && keyMaterialStatus[selectedKey.id] == false) {
                        Text("此密钥缺少私钥，请先在密钥列表中恢复。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onManageKeys) { Text(if (sshKeys.isEmpty()) "先创建 SSH 密钥" else "管理 SSH 密钥") }
                } else {
                    OutlinedTextField(
                        credential,
                        { onDraftChange(draft.copy(credential = it)); error = null },
                        label = { Text(if (authMode == HostAuthMode.PASSWORD) "密码" else "OpenSSH / PEM 私钥内容") },
                        supportingText = if (host != null && !changedAuthentication) ({ Text("留空则保留现有凭据") }) else null,
                        visualTransformation = if (authMode == HostAuthMode.PASSWORD) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        minLines = if (authMode == HostAuthMode.PASTED_KEY) 4 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (authMode == HostAuthMode.PASTED_KEY) {
                        OutlinedTextField(passphrase, { onDraftChange(draft.copy(passphrase = it)) }, label = { Text("私钥口令（可选）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !submitting, onClick = {
                val parsedPort = validPort ?: return@TextButton
                submitting = true
                val authentication = credential.takeIf { it.isNotBlank() }?.let {
                    if (authMode == HostAuthMode.PASSWORD) RemoteAuthentication.Password(it)
                    else RemoteAuthentication.PrivateKey(it, passphrase.takeIf(String::isNotBlank))
                }
                val sshKeyId = selectedKeyId.takeIf { authMode == HostAuthMode.SAVED_KEY && it != host?.sshKeyId }
                if (host == null) {
                    onCreate(name.trim(), hostname.trim(), parsedPort, username.trim(), authentication, selectedKeyId.takeIf { authMode == HostAuthMode.SAVED_KEY }) { result ->
                        if (!active) return@onCreate
                        submitting = false
                        result.fold(onSuccess = { onDismiss() }, onFailure = { error = it.localizedMessage ?: "保存失败" })
                    }
                } else {
                    onUpdate(host.id, name.trim(), hostname.trim(), parsedPort, username.trim(), authentication, sshKeyId) { result ->
                        if (!active) return@onUpdate
                        submitting = false
                        result.fold(
                            onSuccess = { if (it) onDismiss() else error = "未能更新主机" },
                            onFailure = { error = it.localizedMessage ?: "保存失败" },
                        )
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } },
    )
}

internal enum class HostAuthMode(val label: String) {
    PASSWORD("密码"),
    SAVED_KEY("SSH 密钥"),
    PASTED_KEY("粘贴私钥"),
}

internal data class HostEditorDraft(
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authMode: HostAuthMode = HostAuthMode.PASSWORD,
    val selectedKeyId: String? = null,
    val credential: String = "",
    val passphrase: String = "",
) {
    companion object {
        fun fromHost(host: RemoteHostEntity?): HostEditorDraft = if (host == null) HostEditorDraft() else HostEditorDraft(
            name = host.name,
            hostname = host.host,
            port = host.port.toString(),
            username = host.username,
            authMode = when {
                host.sshKeyId != null -> HostAuthMode.SAVED_KEY
                host.authType == "PRIVATE_KEY" -> HostAuthMode.PASTED_KEY
                else -> HostAuthMode.PASSWORD
            },
            selectedKeyId = host.sshKeyId,
        )
    }
}

@Composable
internal fun RemoteHostAuthSelector(mode: HostAuthMode, onModeChange: (HostAuthMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("认证方式", style = MaterialTheme.typography.titleSmall)
        HostAuthMode.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_mode_${option.name.lowercase()}")
                    .selectable(selected = mode == option, role = Role.RadioButton) { onModeChange(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Text(option.label)
            }
        }
    }
}

@Composable
internal fun RemoteHostVerificationDialog(
    host: RemoteHostEntity,
    discover: ((Result<RemoteHostKey>) -> Unit) -> Unit,
    trust: (String, (Result<Boolean>) -> Unit) -> Unit,
    test: ((Result<Boolean>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember(host.id, host.connectionRevision) { mutableStateOf<RemoteHostKey?>(null) }
    var error by remember(host.id, host.connectionRevision) { mutableStateOf<String?>(null) }
    var success by remember(host.id, host.connectionRevision) { mutableStateOf(false) }
    var phase by remember(host.id, host.connectionRevision) { mutableStateOf(VerificationPhase.IDLE) }
    var trustedFingerprint by remember(host.id, host.connectionRevision) { mutableStateOf(host.trustedHostKeySha256) }
    var active by remember(host.id, host.connectionRevision) { mutableStateOf(true) }
    DisposableEffect(host.id, host.connectionRevision) {
        active = true
        onDispose { active = false }
    }
    val busy = phase != VerificationPhase.IDLE
    val keyChanged = trustedFingerprint != null && key != null &&
        trustedFingerprint != key?.sha256Fingerprint

    fun runDiscovery() {
        if (phase != VerificationPhase.IDLE) return
        phase = VerificationPhase.DISCOVERING
        key = null
        success = false
        error = null
        discover { result ->
            if (!active) return@discover
            phase = VerificationPhase.IDLE
            result.fold(onSuccess = { key = it }, onFailure = { error = it.localizedMessage ?: "无法读取主机指纹" })
        }
    }
    LaunchedEffect(host.id, host.connectionRevision) { runDiscovery() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认主机身份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${host.username}@${host.host}:${host.port}")
                Text("请与目标机器上可信渠道显示的 SSH 主机指纹核对，确认后才会保存并连接。", style = MaterialTheme.typography.bodySmall)
                key?.let {
                    Text(it.algorithm, style = MaterialTheme.typography.labelSmall)
                    Text(it.sha256Fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                trustedFingerprint?.let { Text("原已信任：$it", style = MaterialTheme.typography.bodySmall) }
                if (keyChanged) {
                    Text("主机密钥已变化。请先通过可信渠道核对新指纹，确认后才更新信任记录。", color = MaterialTheme.colorScheme.error)
                }
                when (phase) {
                    VerificationPhase.DISCOVERING -> Text("正在读取主机指纹…", style = MaterialTheme.typography.bodySmall)
                    VerificationPhase.TRUSTING -> Text("正在确认主机指纹…", style = MaterialTheme.typography.bodySmall)
                    VerificationPhase.TESTING -> Text("正在测试连接…", style = MaterialTheme.typography.bodySmall)
                    VerificationPhase.IDLE -> Unit
                }
                if (success) Text("主机可连接", color = MaterialTheme.colorScheme.primary)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!busy) TextButton(onClick = ::runDiscovery) { Text("重新读取指纹") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && key != null, onClick = {
                if (phase != VerificationPhase.IDLE) return@TextButton
                error = null
                success = false
                val fingerprint = requireNotNull(key).sha256Fingerprint
                fun runTest() {
                    phase = VerificationPhase.TESTING
                    test { testResult ->
                        if (!active) return@test
                        phase = VerificationPhase.IDLE
                        testResult.fold(
                            onSuccess = { connected -> success = connected; if (!connected) error = "连接失败，请重试" },
                            onFailure = { error = it.localizedMessage ?: "连接失败，请重试" },
                        )
                    }
                }
                if (trustedFingerprint == fingerprint) {
                    runTest()
                } else {
                    phase = VerificationPhase.TRUSTING
                    trust(fingerprint) { trustResult ->
                        if (!active) return@trust
                        trustResult.fold(
                            onSuccess = { trusted ->
                                if (!trusted) {
                                    phase = VerificationPhase.IDLE
                                    error = "指纹已变化，请重新读取并核对"
                                } else {
                                    trustedFingerprint = fingerprint
                                    runTest()
                                }
                            },
                            onFailure = {
                                phase = VerificationPhase.IDLE
                                error = it.localizedMessage ?: "无法确认主机指纹"
                            },
                        )
                    }
                }
            }) { Text(if (keyChanged) "更新信任并测试" else if (trustedFingerprint == key?.sha256Fingerprint) "测试连接" else "信任并测试") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
}

private enum class VerificationPhase { IDLE, DISCOVERING, TRUSTING, TESTING }

internal data class RemoteWorkspaceDraft(
    val name: String = "",
    val selectedHostId: String? = null,
    val directory: String = "",
)

@Composable
internal fun RemoteWorkspaceDialog(
    hosts: List<RemoteHostEntity>,
    existingNames: Set<String>,
    draft: RemoteWorkspaceDraft,
    onDraftChange: (RemoteWorkspaceDraft) -> Unit,
    onDismiss: () -> Unit,
    onAddHost: () -> Unit,
    onVerifyHost: (RemoteHostEntity) -> Unit,
    onCreate: (String, String, String, (Result<WorkspaceEntity>) -> Unit) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    val name = draft.name
    val directory = draft.directory
    val selected = if (draft.selectedHostId == null) hosts.firstOrNull()
        else hosts.find { it.id == draft.selectedHostId }
    val valid = name.trim().isNotEmpty() && name.trim() !in existingNames &&
        selected?.trustedHostKeySha256 != null && directory.startsWith('/') && directory.trimEnd('/') != "" &&
        directory.split('/').none { it == ".." }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建远程工作空间") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { onDraftChange(draft.copy(name = it)); error = null }, label = { Text("工作空间名称") }, modifier = Modifier.fillMaxWidth().testTag("remote_workspace_name"), isError = name.trim() in existingNames)
                Text("选择远程主机", style = MaterialTheme.typography.titleSmall)
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()).selectableGroup(),
                ) {
                    hosts.forEach { host ->
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("remote_workspace_host_${host.id}").selectable(
                                selected = selected?.id == host.id,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = { onDraftChange(draft.copy(selectedHostId = host.id)); error = null },
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected?.id == host.id, onClick = null, enabled = !busy)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${host.username}@${host.host}:${host.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (hosts.isEmpty()) Text("还没有远程主机。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onAddHost, enabled = !busy) { Text("添加远程主机") }
                OutlinedTextField(directory, { onDraftChange(draft.copy(directory = it)); error = null }, label = { Text("远程绝对目录") }, supportingText = { Text("已存在的目录，例如 /home/user/project") }, modifier = Modifier.fillMaxWidth().testTag("remote_workspace_directory"))
                if (selected != null && selected.trustedHostKeySha256 == null) {
                    Text("使用前需确认主机指纹。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onVerifyHost(selected) }, enabled = !busy) { Text("确认指纹并测试") }
                }
                if (busy) Text("正在创建工作空间…", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                if (busy) return@TextButton
                busy = true
                onCreate(name.trim(), requireNotNull(selected).id, directory.trimEnd('/').ifBlank { "/" }) { result ->
                    if (!active) return@onCreate
                    busy = false
                    result.fold(onSuccess = { onDismiss() }, onFailure = { error = it.localizedMessage ?: "创建失败" })
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun RemoteHostDeleteDialog(
    host: RemoteHostEntity,
    onDelete: ((Result<Boolean>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除远程主机") },
        text = {
            Column {
                Text("删除 ${host.name} 的连接配置？关联的工作空间需要先删除。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onDelete { result ->
                    if (!active) return@onDelete
                    busy = false
                    result.fold(
                        onSuccess = { if (it) onDismiss() else error = "请先删除关联的工作空间" },
                        onFailure = { error = it.localizedMessage ?: "删除失败" },
                    )
                }
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
