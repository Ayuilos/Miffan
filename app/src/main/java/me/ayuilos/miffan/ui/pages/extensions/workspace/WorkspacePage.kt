package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.ui.platform.LocalResources
import android.content.res.Resources
import me.ayuilos.miffan.data.repository.RemoteConnectionActivity
import me.ayuilos.miffan.data.repository.RemoteConfigurationState
import me.rerere.workspace.WorkspaceShellStatus
import me.ayuilos.miffan.utils.workspaceErrorMessage
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.app.KeyguardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.ayuilos.miffan.data.repository.RemoteOperationOutcome
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ai.workspaceKindIcon
import me.ayuilos.miffan.ui.components.ai.workspaceKindLabel
import me.ayuilos.miffan.ui.components.ui.MiffanConfirmDialog
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

private data class PendingPrivateKeyExport(val keyId: String, val passphrase: String?, val token: Long)

internal enum class WorkspaceHomeTab { WORKSPACES, CONNECTIONS }
internal enum class WorkspaceConnectionTab { HOSTS, KEYS }
internal enum class WorkspaceListFilter { ALL, LOCAL, REMOTE }

internal fun filterWorkspaceList(
    workspaces: List<WorkspaceEntity>,
    hosts: List<RemoteHostEntity>,
    query: String,
    filter: WorkspaceListFilter,
): List<WorkspaceEntity> {
    val term = query.trim()
    return workspaces.filter { workspace ->
        val matchesType = when (filter) {
            WorkspaceListFilter.ALL -> true
            WorkspaceListFilter.LOCAL -> !workspace.isRemote
            WorkspaceListFilter.REMOTE -> workspace.isRemote
        }
        val host = hosts.find { it.id == workspace.remoteHostId }
        matchesType && (term.isEmpty() || listOfNotNull(
            workspace.name, workspace.remotePath, workspace.root,
            host?.name, host?.host, host?.username,
        ).any { it.contains(term, ignoreCase = true) })
    }
}

internal class WorkspaceHomeActions(
    val createWorkspace: () -> Unit = {},
    val createRemoteWorkspace: () -> Unit = {},
    val addHost: () -> Unit = {},
    val keyActions: () -> Unit = {},
    val openWorkspace: (WorkspaceEntity) -> Unit = {},
    val renameWorkspace: (WorkspaceEntity) -> Unit = {},
    val deleteWorkspace: (WorkspaceEntity) -> Unit = {},
    val editHost: (RemoteHostEntity) -> Unit = {},
    val verifyHost: (RemoteHostEntity) -> Unit = {},
    val deleteHost: (RemoteHostEntity) -> Unit = {},
    val copyPublicKey: (String) -> Unit = {},
    val exportPublicKey: (SshKeyEntity) -> Unit = {},
    val renameKey: (SshKeyEntity) -> Unit = {},
    val deleteKey: (SshKeyEntity) -> Unit = {},
    val restoreKey: (SshKeyEntity) -> Unit = {},
    val privateKey: (SshKeyEntity) -> Unit = {},
)

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val workspaceStrings = LocalResources.current
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val sshKeys by vm.sshKeys.collectAsStateWithLifecycle()
    val keyMaterialStatus by vm.keyMaterialStatus.collectAsStateWithLifecycle()
    val remoteHostStates by vm.remoteHostStates.collectAsStateWithLifecycle()
    val remoteWorkspaceStates by vm.remoteWorkspaceStates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val deviceSecure = keyguard.isDeviceSecure
    val ioScope = rememberCoroutineScope()
    LaunchedEffect(sshKeys) { vm.refreshKeyMaterial(sshKeys) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var createInProgress by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var remoteWorkspaceDraft by remember { mutableStateOf(RemoteWorkspaceDraft()) }
    var hostEditor by remember { mutableStateOf<RemoteHostEntity?>(null) }
    var hostDraft by remember { mutableStateOf<HostEditorDraft?>(null) }
    var showHostEditor by remember { mutableStateOf(false) }
    var hostToDelete by remember { mutableStateOf<RemoteHostEntity?>(null) }
    var hostToVerify by remember { mutableStateOf<RemoteHostEntity?>(null) }
    var returnToRemoteWorkspaceDialog by remember { mutableStateOf(false) }
    var showKeyActions by remember { mutableStateOf(false) }
    var selectedHomeTab by rememberSaveable { mutableStateOf(WorkspaceHomeTab.WORKSPACES) }
    var selectedConnectionTab by rememberSaveable { mutableStateOf(WorkspaceConnectionTab.HOSTS) }
    var workspaceQuery by rememberSaveable { mutableStateOf("") }
    var workspaceFilter by rememberSaveable { mutableStateOf(WorkspaceListFilter.ALL) }
    var showCreateChoice by remember { mutableStateOf(false) }
    var showGenerateKey by remember { mutableStateOf(false) }
    var showImportKey by remember { mutableStateOf(false) }
    var publicKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var renameKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var deleteKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var restoreKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var privateKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var pendingKeyguardTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var pendingPrivateExport by remember { mutableStateOf<PendingPrivateKeyExport?>(null) }
    var privateExportToken by remember { mutableStateOf(0L) }
    var privateExportBusy by remember { mutableStateOf(false) }
    var exportKeyTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
    var resumeHostEditorAfterKey by remember { mutableStateOf(false) }
    val operationFailed = stringResource(R.string.error_title_operation)
    val verifyDeviceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val key = pendingKeyguardTarget.also { pendingKeyguardTarget = null }
        if (result.resultCode == Activity.RESULT_OK && key != null) {
            privateKeyTarget = key
        }
    }
    val privateExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val request = pendingPrivateExport.also { pendingPrivateExport = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null || privateKeyTarget?.id != request.keyId || privateExportToken != request.token) {
            privateExportBusy = false
            return@rememberLauncherForActivityResult
        }
        vm.exportSshPrivateKey(request.keyId, request.passphrase) { result ->
            if (privateKeyTarget?.id != request.keyId || privateExportToken != request.token) {
                privateExportBusy = false
                return@exportSshPrivateKey
            }
            result.fold(
                onSuccess = { privateKeyPem ->
                    ioScope.launch {
                        if (privateKeyTarget?.id != request.keyId || privateExportToken != request.token) return@launch
                        val saved = withContext(Dispatchers.IO) {
                            val bytes = (privateKeyPem.trimEnd() + "\n").toByteArray(Charsets.UTF_8)
                            try {
                                context.contentResolver.openOutputStream(uri)?.use { output ->
                                    output.write(bytes)
                                } ?: error("output unavailable")
                                true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            } finally {
                                bytes.fill(0)
                            }
                        }
                        if (privateKeyTarget?.id == request.keyId && privateExportToken == request.token) {
                            privateExportBusy = false
                            Toast.makeText(context, if (saved) workspaceStrings.getString(R.string.workspace_private_key_exported) else workspaceStrings.getString(R.string.workspace_private_key_export_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onFailure = {
                    privateExportBusy = false
                    Toast.makeText(context, workspaceStrings.getString(R.string.workspace_private_key_export_failed), Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
    fun closePrivateKeyDialog() {
        privateExportToken += 1
        pendingPrivateExport = null
        privateKeyTarget = null
        privateExportBusy = false
    }
    fun requestPrivateKeyAccess(key: SshKeyEntity) {
        if (!deviceSecure) {
            privateKeyTarget = key
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(workspaceStrings.getString(R.string.workspace_view_ssh_private_key), workspaceStrings.getString(R.string.workspace_verify_device_for_key))
        if (intent == null) {
            Toast.makeText(context, workspaceStrings.getString(R.string.workspace_device_auth_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        pendingKeyguardTarget = key
        verifyDeviceLauncher.launch(intent)
    }
    val exportPublicKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val key = exportKeyTarget.also { exportKeyTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write((key.publicKey.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
            } ?: error(workspaceStrings.getString(R.string.workspace_create_public_key_file_failed))
        }.onFailure { Toast.makeText(context, it.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.mermaid_export_failed), Toast.LENGTH_SHORT).show() }
    }
    val copyPublicKey: (String) -> Unit = { publicKey ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey))
        Toast.makeText(context, workspaceStrings.getString(R.string.workspace_public_key_copied), Toast.LENGTH_SHORT).show()
    }
    val exportPublicKey: (SshKeyEntity) -> Unit = { key ->
        exportKeyTarget = key
        exportPublicKeyLauncher.launch("${key.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.pub")
    }
    fun resumeHostEditor() {
        if (resumeHostEditorAfterKey && hostDraft != null) {
            resumeHostEditorAfterKey = false
            showHostEditor = true
        }
    }
    fun openHostEditor(host: RemoteHostEntity?) {
        hostEditor = host
        hostDraft = HostEditorDraft.fromHost(host)
        showHostEditor = true
    }

    val homeActions = WorkspaceHomeActions(
        createWorkspace = { showCreateChoice = true },
        createRemoteWorkspace = { showRemoteDialog = true },
        addHost = {
            returnToRemoteWorkspaceDialog = false
            openHostEditor(null)
        },
        keyActions = { showKeyActions = true },
        openWorkspace = { navController.navigate(Screen.WorkspaceDetail(it.id)) },
        renameWorkspace = { editTarget = it },
        deleteWorkspace = { deleteTarget = it },
        editHost = ::openHostEditor,
        verifyHost = { hostToVerify = it },
        deleteHost = { hostToDelete = it },
        copyPublicKey = copyPublicKey,
        exportPublicKey = exportPublicKey,
        renameKey = { renameKeyTarget = it },
        deleteKey = { deleteKeyTarget = it },
        restoreKey = { restoreKeyTarget = it },
        privateKey = ::requestPrivateKeyAccess,
    )

    Scaffold(
        topBar = {
            WorkspaceHomeToolbar(
                homeTab = selectedHomeTab,
                connectionTab = selectedConnectionTab,
                onBack = { navController.popBackStack() },
                actions = homeActions,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        WorkspaceHomeContent(
            workspaces = workspaces,
            hosts = hosts,
            sshKeys = sshKeys,
            keyMaterialStatus = keyMaterialStatus,
            remoteHostStates = remoteHostStates,
            remoteWorkspaceStates = remoteWorkspaceStates,
            homeTab = selectedHomeTab,
            connectionTab = selectedConnectionTab,
            query = workspaceQuery,
            filter = workspaceFilter,
            onHomeTabChange = { selectedHomeTab = it },
            onConnectionTabChange = { selectedConnectionTab = it },
            onQueryChange = { workspaceQuery = it },
            onFilterChange = { workspaceFilter = it },
            actions = homeActions,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (showCreateChoice) {
        WorkspaceCreateSheet(
            onDismiss = { showCreateChoice = false },
            onLocal = {
                showCreateChoice = false
                createError = null
                showAddDialog = true
            },
            onRemote = { showCreateChoice = false; showRemoteDialog = true },
        )
    }

    if (showKeyActions) {
        SshKeyActionsDialog(
            onGenerate = { showKeyActions = false; showGenerateKey = true },
            onImport = { showKeyActions = false; showImportKey = true },
            onDismiss = { showKeyActions = false; resumeHostEditor() },
        )
    }
    if (showGenerateKey) {
        SshKeyGenerateDialog(
            existingNames = sshKeys.map { it.name }.toSet(),
            onGenerate = vm::generateSshKey,
            onCreated = { publicKeyTarget = it },
            onDismiss = {
                showGenerateKey = false
                if (publicKeyTarget == null) resumeHostEditor()
            },
        )
    }
    if (showImportKey) {
        SshKeyImportDialog(
            existingNames = sshKeys.map { it.name }.toSet(),
            onImport = vm::importSshKey,
            onCreated = { publicKeyTarget = it },
            onDismiss = {
                showImportKey = false
                if (publicKeyTarget == null) resumeHostEditor()
            },
        )
    }
    publicKeyTarget?.let { key ->
        SshKeyPublicKeyDialog(
            key = key,
            onCopy = copyPublicKey,
            onExport = exportPublicKey,
            onDismiss = { publicKeyTarget = null; resumeHostEditor() },
        )
    }
    renameKeyTarget?.let { key ->
        SshKeyRenameDialog(
            key = key,
            existingNames = sshKeys.filter { it.id != key.id }.map { it.name }.toSet(),
            onRename = { name, callback -> vm.renameSshKey(key.id, name, callback) },
            onDismiss = { renameKeyTarget = null },
        )
    }
    deleteKeyTarget?.let { key ->
        SshKeyDeleteDialog(
            key = key,
            onDelete = { callback -> vm.deleteSshKey(key.id, callback) },
            onDismiss = { deleteKeyTarget = null },
        )
    }
    restoreKeyTarget?.let { key ->
        SshKeyRestoreDialog(
            key = key,
            onRestore = { pem, passphrase, callback -> vm.importSshKeyMaterial(key.id, pem, passphrase, callback) },
            onRestored = { vm.refreshKeyMaterial(sshKeys) },
            onDismiss = { restoreKeyTarget = null },
        )
    }
    privateKeyTarget?.let { key ->
        SshKeyPrivateKeyDialog(
            key = key,
            deviceSecure = deviceSecure,
            exportBusy = privateExportBusy,
            onView = { callback -> vm.exportSshPrivateKey(key.id, null, callback) },
            onExport = { passphrase ->
                privateExportToken += 1
                pendingPrivateExport = PendingPrivateKeyExport(key.id, passphrase, privateExportToken)
                privateExportBusy = true
                privateExportLauncher.launch("${key.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}_private.key")
            },
            onDismiss = ::closePrivateKeyDialog,
        )
    }

    if (showRemoteDialog) {
        RemoteWorkspaceDialog(
            hosts = hosts,
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            draft = remoteWorkspaceDraft,
            onDraftChange = { remoteWorkspaceDraft = it },
            onDismiss = {
                showRemoteDialog = false
                returnToRemoteWorkspaceDialog = false
                remoteWorkspaceDraft = RemoteWorkspaceDraft()
            },
            onAddHost = {
                showRemoteDialog = false
                returnToRemoteWorkspaceDialog = true
                openHostEditor(null)
            },
            onVerifyHost = { host ->
                showRemoteDialog = false
                returnToRemoteWorkspaceDialog = true
                hostToVerify = host
            },
            onCreate = { name, hostId, path, callback -> vm.createRemote(name, hostId, path, callback) },
        )
    }
    if (showHostEditor && hostDraft != null) {
        RemoteHostEditorDialog(
            host = hostEditor,
            sshKeys = sshKeys,
            keyMaterialStatus = keyMaterialStatus,
            draft = requireNotNull(hostDraft),
            onDraftChange = { hostDraft = it },
            onDismiss = {
                showHostEditor = false
                hostDraft = null
                if (returnToRemoteWorkspaceDialog && hostToVerify == null) {
                    returnToRemoteWorkspaceDialog = false
                    showRemoteDialog = true
                }
            },
            onManageKeys = {
                showHostEditor = false
                resumeHostEditorAfterKey = true
                showKeyActions = true
            },
            onCreate = { name, hostname, port, username, auth, sshKeyId, callback ->
                vm.createHost(name, hostname, port, username, auth, sshKeyId) { result ->
                    result.getOrNull()?.let { created ->
                        if (showHostEditor) {
                            if (returnToRemoteWorkspaceDialog) {
                                remoteWorkspaceDraft = remoteWorkspaceDraft.copy(selectedHostId = created.id)
                            }
                            hostToVerify = created
                        }
                    }
                    callback(result)
                }
            },
            onUpdate = { id, name, hostname, port, username, auth, sshKeyId, callback ->
                val previous = hostEditor
                vm.updateHost(id, name, hostname, port, username, auth, sshKeyId) { result ->
                    if (result.getOrNull() == true && previous != null &&
                        (previous.host != hostname || previous.port != port || previous.username != username || auth != null ||
                            (sshKeyId != null && sshKeyId != previous.sshKeyId))
                    ) {
                        vm.getHost(id) { refreshed ->
                            if (showHostEditor && hostEditor?.id == id) {
                                val current = refreshed.getOrNull()
                                if (current != null) {
                                    hostToVerify = current
                                    callback(result)
                                } else {
                                    callback(Result.failure(IllegalStateException(workspaceStrings.getString(R.string.workspace_saved_host_reload_failed))))
                                }
                            }
                        }
                    } else {
                        callback(result)
                    }
                }
            },
        )
    }
    hostToVerify?.let { host ->
        RemoteHostVerificationDialog(
            host = host,
            discover = { callback -> vm.discoverHostKey(host.id, callback) },
            trust = { fingerprint, callback -> vm.trustHostKey(host.id, fingerprint, callback) },
            test = { callback -> vm.testHost(host.id, callback) },
            onDismiss = {
                hostToVerify = null
                if (returnToRemoteWorkspaceDialog) {
                    returnToRemoteWorkspaceDialog = false
                    showRemoteDialog = true
                }
            },
        )
    }
    hostToDelete?.let { host ->
        RemoteHostDeleteDialog(
            host = host,
            onDelete = { callback -> vm.deleteHost(host.id, callback) },
            onDismiss = { hostToDelete = null },
        )
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            errorMessage = createError,
            submitting = createInProgress,
            onInputChange = { createError = null },
            onDismiss = {
                if (!createInProgress) {
                    createError = null
                    showAddDialog = false
                }
            },
            onConfirm = { name ->
                createInProgress = true
                createError = null
                vm.create(name) { result ->
                    createInProgress = false
                    result.fold(
                        onSuccess = {
                            createError = null
                            showAddDialog = false
                        },
                        onFailure = { error ->
                            createError = error.workspaceErrorMessage(workspaceStrings)?.takeIf(String::isNotBlank)
                                ?: operationFailed
                        },
                    )
                }
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    MiffanConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
internal fun WorkspaceHomeToolbar(
    homeTab: WorkspaceHomeTab,
    connectionTab: WorkspaceConnectionTab,
    onBack: () -> Unit,
    actions: WorkspaceHomeActions,
) {
    val workspaceStrings = LocalResources.current
    TopAppBar(
        title = { Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, contentDescription = workspaceStrings.getString(R.string.back)) } },
        actions = {
            TextButton(
                onClick = when {
                    homeTab == WorkspaceHomeTab.WORKSPACES -> actions.createWorkspace
                    connectionTab == WorkspaceConnectionTab.HOSTS -> actions.addHost
                    else -> actions.keyActions
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(end = 8.dp).testTag("workspace-primary-action"),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (homeTab == WorkspaceHomeTab.WORKSPACES) workspaceStrings.getString(R.string.chat_page_folder_add) else workspaceStrings.getString(R.string.setting_tts_page_add), modifier = Modifier.padding(start = 6.dp))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun WorkspaceNavigationItem(label: String, selected: Boolean, tag: String, quiet: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(tag).heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = if (selected && !quiet) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            shape = RoundedCornerShape(12.dp)) {
            Text(label, style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun WorkspaceTypeMark(remote: Boolean) {
    val workspaceStrings = LocalResources.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(workspaceKindIcon(remote), contentDescription = workspaceKindLabel(workspaceStrings, remote), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun WorkspaceCreateSheet(onDismiss: () -> Unit, onLocal: () -> Unit, onRemote: () -> Unit) {
    val workspaceStrings = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text(stringResource(R.string.workspace_new_workspace), style = MaterialTheme.typography.headlineSmall)
            Text(
                workspaceStrings.getString(R.string.workspace_choose_location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            WorkspaceCreateOption(false, workspaceStrings.getString(R.string.workspace_local_workspace), workspaceStrings.getString(R.string.workspace_local_workspace_description), onLocal)
            WorkspaceCreateOption(true, workspaceStrings.getString(R.string.workspace_remote_workspace), workspaceStrings.getString(R.string.workspace_remote_workspace_description), onRemote)
        }
    }
}

@Composable
private fun WorkspaceCreateOption(remote: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkspaceTypeMark(remote)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun WorkspaceHomeContent(
    workspaces: List<WorkspaceEntity>,
    hosts: List<RemoteHostEntity>,
    sshKeys: List<SshKeyEntity>,
    keyMaterialStatus: Map<String, Boolean>,
    remoteHostStates: Map<String, RemoteHostRuntimeState>,
    remoteWorkspaceStates: Map<String, RemoteWorkspaceRuntimeState>,
    homeTab: WorkspaceHomeTab,
    connectionTab: WorkspaceConnectionTab,
    query: String,
    filter: WorkspaceListFilter,
    onHomeTabChange: (WorkspaceHomeTab) -> Unit,
    onConnectionTabChange: (WorkspaceConnectionTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (WorkspaceListFilter) -> Unit,
    actions: WorkspaceHomeActions,
    modifier: Modifier = Modifier,
) {
    val workspaceStrings = LocalResources.current
    val visibleWorkspaces = filterWorkspaceList(workspaces, hosts, query, filter)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WorkspaceNavigationItem(workspaceStrings.getString(R.string.workspace_spaces_tab), homeTab == WorkspaceHomeTab.WORKSPACES, "workspace-tab-workspaces") {
                onHomeTabChange(WorkspaceHomeTab.WORKSPACES)
            }
            WorkspaceNavigationItem(workspaceStrings.getString(R.string.workspace_hosts_tab), homeTab == WorkspaceHomeTab.CONNECTIONS && connectionTab == WorkspaceConnectionTab.HOSTS, "workspace-tab-hosts") {
                onHomeTabChange(WorkspaceHomeTab.CONNECTIONS)
                onConnectionTabChange(WorkspaceConnectionTab.HOSTS)
            }
            WorkspaceNavigationItem(workspaceStrings.getString(R.string.workspace_keys_tab), homeTab == WorkspaceHomeTab.CONNECTIONS && connectionTab == WorkspaceConnectionTab.KEYS, "workspace-tab-keys") {
                onHomeTabChange(WorkspaceHomeTab.CONNECTIONS)
                onConnectionTabChange(WorkspaceConnectionTab.KEYS)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (homeTab) {
                WorkspaceHomeTab.WORKSPACES -> {
                    if (workspaces.isNotEmpty() || query.isNotEmpty()) item {
                        TextField(
                            value = query,
                            onValueChange = onQueryChange,
                            placeholder = { Text(stringResource(R.string.workspace_search_spaces), style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            shape = RoundedCornerShape(16.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            singleLine = true,
                            trailingIcon = if (query.isBlank()) null else {
                                { TextButton(onClick = { onQueryChange("") }) { Text(stringResource(R.string.chat_page_conversation_system_prompt_clear)) } }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("workspace-search"),
                        )
                    }
                    if (workspaces.isNotEmpty()) item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                WorkspaceListFilter.ALL to workspaceStrings.getString(R.string.workspace_filter_all),
                                WorkspaceListFilter.LOCAL to workspaceStrings.getString(R.string.workspace_filter_local),
                                WorkspaceListFilter.REMOTE to workspaceStrings.getString(R.string.workspace_filter_remote),
                            ).forEach { (option, label) ->
                                WorkspaceNavigationItem(
                                    label = label,
                                    selected = filter == option,
                                    tag = "workspace-filter-${option.name.lowercase()}",
                                    quiet = true,
                                    onClick = { onFilterChange(option) },
                                )
                            }
                        }
                    }
                    if (visibleWorkspaces.isEmpty()) {
                        item {
                            val hasQuery = query.isNotBlank()
                            val hasAny = workspaces.isNotEmpty()
                            val clearFilters: () -> Unit = {
                                onQueryChange("")
                                onFilterChange(WorkspaceListFilter.ALL)
                            }
                            WorkspaceEmptyState(
                                title = when {
                                    hasQuery -> workspaceStrings.getString(R.string.workspace_no_matches)
                                    !hasAny -> workspaceStrings.getString(R.string.workspace_empty_workspaces)
                                    filter == WorkspaceListFilter.REMOTE -> workspaceStrings.getString(R.string.workspace_empty_remote_workspaces)
                                    else -> workspaceStrings.getString(R.string.workspace_empty_local_workspaces)
                                },
                                description = when {
                                    hasQuery -> workspaceStrings.getString(R.string.workspace_search_hint)
                                    !hasAny -> workspaceStrings.getString(R.string.workspace_empty_workspaces_help)
                                    else -> workspaceStrings.getString(R.string.workspace_empty_filter_help)
                                },
                                actionLabel = if (hasQuery) workspaceStrings.getString(R.string.workspace_clear_filters)
                                    else if (filter == WorkspaceListFilter.REMOTE) workspaceStrings.getString(R.string.workspace_new_remote_workspace)
                                    else workspaceStrings.getString(R.string.workspace_new_workspace),
                                onAction = when {
                                    hasQuery -> clearFilters
                                    filter == WorkspaceListFilter.REMOTE -> actions.createRemoteWorkspace
                                    else -> actions.createWorkspace
                                },
                            )
                        }
                    } else {
                        items(visibleWorkspaces, key = { it.id }) { workspace ->
                            WorkspaceCard(
                                workspace = workspace,
                                host = hosts.find { it.id == workspace.remoteHostId },
                                hostState = workspace.remoteHostId?.let(remoteHostStates::get),
                                workspaceState = remoteWorkspaceStates[workspace.id],
                                onRename = { actions.renameWorkspace(workspace) },
                                onDelete = { actions.deleteWorkspace(workspace) },
                                onOpen = { actions.openWorkspace(workspace) },
                            )
                        }
                    }
                }
                WorkspaceHomeTab.CONNECTIONS -> when (connectionTab) {
                    WorkspaceConnectionTab.HOSTS -> {
                        if (hosts.isEmpty()) item {
                            WorkspaceEmptyState(
                                title = workspaceStrings.getString(R.string.workspace_empty_hosts),
                                description = workspaceStrings.getString(R.string.workspace_empty_hosts_help),
                                actionLabel = workspaceStrings.getString(R.string.workspace_add_host),
                                onAction = actions.addHost,
                            )
                        }
                        items(hosts, key = { "host:${it.id}" }) { host ->
                            RemoteHostCard(
                                host = host,
                                keyName = sshKeys.find { it.id == host.sshKeyId }?.name,
                                runtime = remoteHostStates[host.id],
                                onEdit = { actions.editHost(host) },
                                onVerify = { actions.verifyHost(host) },
                                onDelete = { actions.deleteHost(host) },
                            )
                        }
                    }
                    WorkspaceConnectionTab.KEYS -> {
                        if (sshKeys.isEmpty()) item {
                            WorkspaceEmptyState(
                                title = workspaceStrings.getString(R.string.workspace_empty_keys),
                                description = workspaceStrings.getString(R.string.workspace_empty_keys_help),
                                actionLabel = workspaceStrings.getString(R.string.workspace_generate_import_key),
                                onAction = actions.keyActions,
                            )
                        }
                        items(sshKeys, key = { "ssh-key:${it.id}" }) { key ->
                            SshKeyCard(
                                key = key,
                                hasPrivateMaterial = keyMaterialStatus[key.id],
                                onCopyPublicKey = actions.copyPublicKey,
                                onExportPublicKey = actions.exportPublicKey,
                                onRename = { actions.renameKey(key) },
                                onDelete = { actions.deleteKey(key) },
                                onRestorePrivateKey = { actions.restoreKey(key) },
                                onPrivateKey = { actions.privateKey(key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEmptyState(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 64.dp)
            .testTag("workspace-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(bottom = 16.dp).size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(workspaceKindIcon(false), contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp).testTag("workspace-empty-action"),
        ) { Text(actionLabel) }
    }
}

@Composable
internal fun WorkspaceCard(
    workspace: WorkspaceEntity,
    host: RemoteHostEntity?,
    hostState: RemoteHostRuntimeState?,
    workspaceState: RemoteWorkspaceRuntimeState?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val workspaceStrings = LocalResources.current
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("workspace-card-${workspace.id}")
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 0.dp, top = 16.dp, bottom = 16.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceTypeMark(workspace.isRemote)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (workspace.isRemote) workspaceStrings.getString(R.string.workspace_remote_name, host?.name ?: workspaceStrings.getString(R.string.workspace_host_unavailable)) else workspaceStrings.getString(R.string.workspace_local_this_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (workspace.isRemote) Text(
                        text = workspaceCardRemoteStatus(workspaceStrings, host, workspace, hostState, workspaceState),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(HugeIcons.MoreVertical, contentDescription = workspaceStrings.getString(R.string.workspace_actions))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun workspaceCardRemoteStatus(workspaceStrings: Resources,
    host: RemoteHostEntity?,
    workspace: WorkspaceEntity,
    hostState: RemoteHostRuntimeState?,
    workspaceState: RemoteWorkspaceRuntimeState?,
): String {
    if (host == null) return workspaceStrings.getString(R.string.workspace_host_config_unavailable)
    if (workspaceState?.lastOperation?.outcome == RemoteOperationOutcome.OUTCOME_UNKNOWN) {
        return workspaceStrings.getString(R.string.workspace_last_outcome_unknown)
    }
    // Select compact status from structured state, never by matching translated text.
    if (workspaceState?.activity == RemoteConnectionActivity.CONNECTING ||
        workspaceState?.activity == RemoteConnectionActivity.OPERATING ||
        hostState?.configuration == RemoteConfigurationState.HOST_KEY_UNTRUSTED ||
        hostState?.configuration == RemoteConfigurationState.CREDENTIAL_MISSING ||
        hostState?.configuration == RemoteConfigurationState.INVALID
    ) return remoteWorkspaceStatusLabel(workspaceStrings, workspace, hostState, workspaceState)
    val directory = workspaceState?.lastDirectoryCheck
    val connection = hostState?.lastConnection
    val label = when {
        connection?.success == false && (directory == null || connection.timestampMillis >= directory.timestampMillis) -> R.string.workspace_last_connection_failed
        directory?.success == false -> R.string.workspace_directory_check_failed
        directory?.success == true -> R.string.workspace_on_demand_directory_passed
        connection?.success == true -> R.string.workspace_on_demand_host_passed
        workspace.shellStatus == WorkspaceShellStatus.READY.name -> R.string.workspace_on_demand_pending
        else -> return remoteWorkspaceStatusLabel(workspaceStrings, workspace, hostState, workspaceState)
    }
    return workspaceStrings.getString(label)
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    errorMessage: String? = null,
    submitting: Boolean = false,
    onInputChange: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onInputChange()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                isError = isDuplicate || errorMessage != null,
                supportingText = when {
                    isDuplicate -> {
                        { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                    }

                    errorMessage != null -> {
                        { Text(errorMessage) }
                    }

                    else -> null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate && !submitting,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
