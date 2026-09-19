package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.utils.workspaceErrorMessage
import me.ayuilos.miffan.R
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
            require(output.size() + count <= MAX_PRIVATE_KEY_FILE_BYTES) { context.getString(R.string.workspace_private_key_file_too_large) }
            output.write(chunk, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    } ?: error(context.getString(R.string.workspace_read_private_key_file_failed))
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
    val workspaceStrings = LocalResources.current
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
                if (hasPrivateMaterial == false) workspaceStrings.getString(R.string.workspace_private_key_restore_pending, key.fingerprint) else key.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasPrivateMaterial == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.size(48.dp)) {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxSize()) {
                Icon(HugeIcons.MoreVertical, contentDescription = workspaceStrings.getString(R.string.workspace_ssh_key_actions))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.workspace_copy_public_key)) }, onClick = { menuExpanded = false; onCopyPublicKey(key.publicKey) })
                DropdownMenuItem(text = { Text(stringResource(R.string.workspace_export_public_key)) }, onClick = { menuExpanded = false; onExportPublicKey(key) })
                if (hasPrivateMaterial != false) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_view_backup_private_key)) }, onClick = { menuExpanded = false; onPrivateKey() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_page_rename)) }, onClick = { menuExpanded = false; onRename() })
                if (hasPrivateMaterial == false) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_restore_private_key)) }, onClick = { menuExpanded = false; onRestorePrivateKey() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.workspace_delete_key)) }, onClick = { menuExpanded = false; onDelete() })
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
    val workspaceStrings = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_ssh_key)) },
        text = { Text(stringResource(R.string.workspace_key_setup_help)) },
        confirmButton = { TextButton(onClick = onGenerate) { Text(stringResource(R.string.workspace_generate_key)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onImport) { Text(stringResource(R.string.workspace_import_private_key)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_close)) }
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
    val workspaceStrings = LocalResources.current
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_generate_ssh_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_generate_ssh_key_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.workspace_key_name)) }, modifier = Modifier.fillMaxWidth().testTag("ssh_key_name"), isError = trimmed in existingNames)
                if (trimmed in existingNames) Text(stringResource(R.string.workspace_page_name_duplicate), color = MaterialTheme.colorScheme.error)
                if (busy) Text(stringResource(R.string.workspace_generating))
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
                        onFailure = { error = it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_generation_failed) },
                    )
                }
            }) { Text(stringResource(R.string.workspace_generate)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun SshKeyImportDialog(
    existingNames: Set<String>,
    onImport: (String, String, String?, (Result<SshKeyEntity>) -> Unit) -> Unit,
    onCreated: (SshKeyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
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
                error = failure.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_read_private_key_failed)
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_import_ssh_private_key)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.workspace_key_name)) }, modifier = Modifier.fillMaxWidth(), isError = trimmed in existingNames)
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.workspace_choose_private_key_file)) }
                OutlinedTextField(privateKey, { privateKey = it; error = null }, label = { Text(stringResource(R.string.workspace_private_key_content)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.workspace_private_key_passphrase_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.workspace_private_key_usage_help), style = MaterialTheme.typography.bodySmall)
                if (busy) Text(stringResource(R.string.workspace_importing))
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
                        onFailure = { error = it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.assistant_importer_import_failed) },
                    )
                }
            }) { Text(stringResource(R.string.setting_theme_page_import_theme)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun SshKeyPublicKeyDialog(
    key: SshKeyEntity,
    onCopy: (String) -> Unit,
    onExport: (SshKeyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_public_key_for, key.name)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_install_public_key_help), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(key.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(key.fingerprint, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(key.publicKey) }) { Text(stringResource(R.string.workspace_copy_public_key)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onExport(key) }) { Text(stringResource(R.string.workspace_export_public_key)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_close)) }
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
    val workspaceStrings = LocalResources.current
    var name by remember(key.id) { mutableStateOf(key.name) }
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var busy by remember(key.id) { mutableStateOf(false) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_rename_ssh_key)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.workspace_key_name)) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && trimmed !in existingNames && !busy, onClick = {
                busy = true
                onRename(trimmed) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) onDismiss() else error = workspaceStrings.getString(R.string.workspace_rename_failed) },
                        onFailure = { error = it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_rename_failed) },
                    )
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun SshKeyDeleteDialog(
    key: SshKeyEntity,
    onDelete: ((Result<Boolean>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
    var error by remember(key.id) { mutableStateOf<String?>(null) }
    var busy by remember(key.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_delete_ssh_key)) },
        text = {
            Column {
                Text(stringResource(R.string.workspace_delete_key_confirmation, key.name))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                onDelete { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) onDismiss() else error = workspaceStrings.getString(R.string.skill_detail_page_delete_failed) },
                        onFailure = { error = it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_delete_key_linked_failed) },
                    )
                }
            }) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun SshKeyRestoreDialog(
    key: SshKeyEntity,
    onRestore: (String, String?, (Result<Boolean>) -> Unit) -> Unit,
    onRestored: () -> Unit,
    onDismiss: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
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
                error = failure.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_read_private_key_failed)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_restore_private_key_for, key.name)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_restore_private_key_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.workspace_choose_private_key_file)) }
                OutlinedTextField(privateKey, { privateKey = it; error = null }, label = { Text(stringResource(R.string.workspace_private_key_content)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.workspace_private_key_passphrase_optional)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = privateKey.isNotBlank() && !busy, onClick = {
                busy = true
                onRestore(privateKey, passphrase.takeIf(String::isNotBlank)) { result ->
                    busy = false
                    result.fold(
                        onSuccess = { if (it) { onRestored(); onDismiss() } else error = workspaceStrings.getString(R.string.workspace_key_mismatch) },
                        onFailure = { error = it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_restore_failed) },
                    )
                }
            }) { Text(stringResource(R.string.workspace_restore)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
    val workspaceStrings = LocalResources.current
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
        title = { Text(stringResource(R.string.workspace_private_key_for, key.name)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!deviceSecure) {
                    Text(stringResource(R.string.workspace_no_screen_lock_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.workspace_device_verified), style = MaterialTheme.typography.bodySmall)
                }
                if (revealedPrivateKey == null) {
                    Text(stringResource(R.string.workspace_private_key_hidden), style = MaterialTheme.typography.bodyMedium)
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
                                onFailure = { error = workspaceStrings.getString(R.string.workspace_view_private_key_failed) },
                            )
                        }
                    }) { Text(if (viewBusy) workspaceStrings.getString(R.string.workspace_reading) else workspaceStrings.getString(R.string.workspace_view_private_key)) }
                } else {
                    SelectionContainer {
                        Text(
                            text = requireNotNull(revealedPrivateKey),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { revealedPrivateKey = null }) { Text(stringResource(R.string.workspace_hide_private_key)) }
                }

                Text(stringResource(R.string.workspace_export_backup), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(selected = encryptedBackup, role = Role.RadioButton) { encryptedBackup = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = encryptedBackup, onClick = null)
                    Text(stringResource(R.string.workspace_encrypted_openssh_recommended))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(selected = !encryptedBackup, role = Role.RadioButton) { encryptedBackup = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = !encryptedBackup, onClick = null)
                    Text(stringResource(R.string.workspace_unencrypted_backup))
                }
                if (encryptedBackup) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; error = null },
                        label = { Text(stringResource(R.string.workspace_backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("private_backup_passphrase"),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text(stringResource(R.string.workspace_confirm_backup_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("private_backup_confirm"),
                        isError = confirmation.isNotEmpty() && passphrase != confirmation,
                    )
                } else {
                    Text(stringResource(R.string.workspace_unencrypted_backup_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
            }) { Text(if (exportBusy) workspaceStrings.getString(R.string.workspace_exporting) else workspaceStrings.getString(R.string.workspace_export_private_key)) }
        },
        dismissButton = { TextButton(enabled = !exportBusy, onClick = ::close) { Text(stringResource(R.string.workspace_close)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
