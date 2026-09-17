package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.MoreVertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_PRIVATE_KEY_FILE_BYTES = 1024 * 1024

private suspend fun readPrivateKeyFile(context: android.content.Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(output.size() + count <= MAX_PRIVATE_KEY_FILE_BYTES) { "私钥文件超过 1 MiB" }
            output.write(chunk, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    } ?: error("无法读取私钥文件")
}

@Composable
internal fun SshKeyCard(
    key: SshKeyEntity,
    hasPrivateMaterial: Boolean?,
    onCopyPublicKey: (String) -> Unit,
    onExportPublicKey: (SshKeyEntity) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRestorePrivateKey: () -> Unit,
    onPrivateKey: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
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
                HugeIcons.Key01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(key.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                key.algorithm,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hasPrivateMaterial == false) "私钥待恢复 · ${key.fingerprint}" else key.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasPrivateMaterial == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.size(48.dp)) {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxSize()) {
                Icon(HugeIcons.MoreVertical, contentDescription = "SSH 密钥操作")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("复制公钥") }, onClick = { menuExpanded = false; onCopyPublicKey(key.publicKey) })
                DropdownMenuItem(text = { Text("导出 .pub") }, onClick = { menuExpanded = false; onExportPublicKey(key) })
                if (hasPrivateMaterial != false) {
                    DropdownMenuItem(text = { Text("查看 / 备份私钥") }, onClick = { menuExpanded = false; onPrivateKey() })
                }
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuExpanded = false; onRename() })
                if (hasPrivateMaterial == false) {
                    DropdownMenuItem(text = { Text("恢复私钥") }, onClick = { menuExpanded = false; onRestorePrivateKey() })
                }
                DropdownMenuItem(text = { Text("删除密钥") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun SshKeyActionsDialog(
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 密钥") },
        text = { Text("可以先生成密钥、复制公钥并安装到服务器，再创建远程主机。") },
        confirmButton = { TextButton(onClick = onGenerate) { Text("生成密钥") } },
        dismissButton = {
            Row {
                TextButton(onClick = onImport) { Text("导入私钥") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
internal fun SshKeyGenerateDialog(
    existingNames: Set<String>,
    onGenerate: (String, (Result<SshKeyEntity>) -> Unit) -> Unit,
    onCreated: (SshKeyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("生成 SSH 密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("生成 Ed25519 密钥。私钥保存在本设备的 APP 中，不包含在自动备份里；生成后可手动导出口令加密备份。公钥可安装到目标机器的 authorized_keys。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("密钥名称") }, modifier = Modifier.fillMaxWidth().testTag("ssh_key_name"), isError = trimmed in existingNames)
                if (trimmed in existingNames) Text("名称已存在", color = MaterialTheme.colorScheme.error)
                if (busy) Text("正在生成…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed !in existingNames && !busy, onClick = {
                busy = true
                onGenerate(trimmed) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { onCreated(it); onDismiss() },
                        onFailure = { error = it.localizedMessage ?: "生成失败" },
                    )
                }
            }) { Text("生成") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SshKeyImportDialog(
    existingNames: Set<String>,
    onImport: (String, String, String?, (Result<SshKeyEntity>) -> Unit) -> Unit,
    onCreated: (SshKeyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                privateKey = readPrivateKeyFile(context, uri)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "读取私钥失败"
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导入 SSH 私钥") },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("密钥名称") }, modifier = Modifier.fillMaxWidth(), isError = trimmed in existingNames)
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("选择私钥文件") }
                OutlinedTextField(privateKey, { privateKey = it; error = null }, label = { Text("OpenSSH / PEM 私钥内容") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("私钥仅用于连接；列表和复制操作只展示公钥。", style = MaterialTheme.typography.bodySmall)
                if (busy) Text("正在导入…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed !in existingNames && privateKey.isNotBlank() && !busy, onClick = {
                busy = true
                onImport(trimmed, privateKey, passphrase.takeIf(String::isNotBlank)) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { onCreated(it); onDismiss() },
                        onFailure = { error = it.localizedMessage ?: "导入失败" },
                    )
                }
            }) { Text("导入") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SshKeyPublicKeyDialog(
    key: SshKeyEntity,
    onCopy: (String) -> Unit,
    onExport: (SshKeyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${key.name} 的公钥") },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将完整公钥添加到目标账户的 ~/.ssh/authorized_keys。", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(key.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(key.fingerprint, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(key.publicKey) }) { Text("复制公钥") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onExport(key) }) { Text("导出 .pub") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
internal fun SshKeyRenameDialog(
    key: SshKeyEntity,
    existingNames: Set<String>,
    onRename: (String, (Result<Boolean>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(key.id) { mutableStateOf(key.name) }
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var busy by remember(key.id) { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名 SSH 密钥") },
        text = {
            Column {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("密钥名称") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed !in existingNames && !busy, onClick = {
                busy = true
                onRename(trimmed) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) onDismiss() else error = "重命名失败" },
                        onFailure = { error = it.localizedMessage ?: "重命名失败" },
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SshKeyDeleteDialog(
    key: SshKeyEntity,
    onDelete: ((Result<Boolean>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var busy by remember(key.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 SSH 密钥") },
        text = {
            Column {
                Text("删除 ${key.name}？正在被主机使用的密钥须先解除绑定。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onDelete { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) onDismiss() else error = "删除失败" },
                        onFailure = { error = it.localizedMessage ?: "删除失败；请先解除主机绑定" },
                    )
                }
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SshKeyRestoreDialog(
    key: SshKeyEntity,
    onRestore: (String, String?, (Result<Boolean>) -> Unit) -> Unit,
    onRestored: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var privateKey by remember(key.id) { mutableStateOf("") }
    var passphrase by remember(key.id) { mutableStateOf("") }
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var busy by remember(key.id) { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                privateKey = readPrivateKeyFile(context, uri)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "读取私钥失败"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复 ${key.name} 的私钥") },
        text = {
            Column(modifier = Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入与此公钥匹配的私钥，才能继续使用已绑定的主机。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("选择私钥文件") }
                OutlinedTextField(privateKey, { privateKey = it; error = null }, label = { Text("OpenSSH / PEM 私钥内容") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（可选）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = privateKey.isNotBlank() && !busy, onClick = {
                busy = true
                onRestore(privateKey, passphrase.takeIf(String::isNotBlank)) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) { onRestored(); onDismiss() } else error = "私钥与公钥不匹配" },
                        onFailure = { error = it.localizedMessage ?: "恢复失败" },
                    )
                }
            }) { Text("恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SshKeyPrivateKeyDialog(
    key: SshKeyEntity,
    deviceSecure: Boolean,
    exportBusy: Boolean,
    onView: ((Result<String>) -> Unit) -> Unit,
    onExport: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var revealedPrivateKey by remember(key.id) { mutableStateOf<String?>(null) }
    var viewBusy by remember(key.id) { mutableStateOf(false) }
    var encryptedBackup by remember(key.id) { mutableStateOf(true) }
    var passphrase by remember(key.id) { mutableStateOf("") }
    var confirmation by remember(key.id) { mutableStateOf("") }
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var active by remember(key.id) { mutableStateOf(true) }
    var viewRequestToken by remember(key.id) { mutableStateOf(0L) }
    DisposableEffect(key.id, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewRequestToken += 1
                viewBusy = false
                revealedPrivateKey = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            active = false
            viewRequestToken += 1
            revealedPrivateKey = null
            passphrase = ""
            confirmation = ""
        }
    }
    fun close() {
        active = false
        viewRequestToken += 1
        revealedPrivateKey = null
        passphrase = ""
        confirmation = ""
        onDismiss()
    }
    val validBackup = !encryptedBackup || (passphrase.isNotBlank() && passphrase == confirmation)

    AlertDialog(
        onDismissRequest = { if (!exportBusy) close() },
        title = { Text("${key.name} 的私钥") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!deviceSecure) {
                    Text("此设备未设置屏幕锁。仍可查看和导出私钥，请确保周围无人窥视并妥善保管备份。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("已通过设备身份验证。私钥仅在主动查看时显示。", style = MaterialTheme.typography.bodySmall)
                }
                if (revealedPrivateKey == null) {
                    Text("私钥已隐藏。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !viewBusy, onClick = {
                        viewBusy = true
                        error = null
                        viewRequestToken += 1
                        val requestToken = viewRequestToken
                        onView { result ->
                            if (!active || requestToken != viewRequestToken) return@onView
                            viewBusy = false
                            result.fold(
                                onSuccess = { revealedPrivateKey = it },
                                onFailure = { error = "无法查看私钥，请重试" },
                            )
                        }
                    }) { Text(if (viewBusy) "正在读取…" else "查看私钥") }
                } else {
                    SelectionContainer {
                        Text(
                            text = requireNotNull(revealedPrivateKey),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { revealedPrivateKey = null }) { Text("隐藏私钥") }
                }

                Text("导出备份", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(selected = encryptedBackup, role = Role.RadioButton) { encryptedBackup = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = encryptedBackup, onClick = null)
                    Text("口令加密 OpenSSH（推荐）")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(selected = !encryptedBackup, role = Role.RadioButton) { encryptedBackup = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !encryptedBackup, onClick = null)
                    Text("无口令备份")
                }
                if (encryptedBackup) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; error = null },
                        label = { Text("备份口令") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("private_backup_passphrase"),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text("确认备份口令") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("private_backup_confirm"),
                        isError = confirmation.isNotEmpty() && passphrase != confirmation,
                    )
                } else {
                    Text("无口令备份的私钥可被任何取得文件的人直接使用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = validBackup && !exportBusy, onClick = {
                error = null
                val chosenPassphrase = if (encryptedBackup) passphrase else null
                onExport(chosenPassphrase)
                passphrase = ""
                confirmation = ""
            }) { Text(if (exportBusy) "正在导出…" else "导出私钥") }
        },
        dismissButton = { TextButton(enabled = !exportBusy, onClick = ::close) { Text("关闭") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
