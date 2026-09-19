package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.ui.platform.LocalResources
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.tools.resolveWorkspaceToolApproval
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.ayuilos.miffan.data.repository.RemoteConnectionStatus
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import kotlinx.coroutines.awaitCancellation
import org.koin.compose.koinInject
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.files.SkillMetadata
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.components.ai.workspaceKindIcon
import me.ayuilos.miffan.ui.components.ai.workspaceKindLabel
import me.ayuilos.miffan.ui.components.ui.MiffanConfirmDialog
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.theme.CustomColors
import me.ayuilos.miffan.utils.fileSizeToString
import me.ayuilos.miffan.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal const val FILES_PAGE = 0
private const val SETTINGS_PAGE = 1
private const val SKILLS_PAGE = 2

internal fun workspaceDetailInitialLocation(
    area: String?,
    path: String?,
): Pair<WorkspaceStorageArea, String> =
    runCatching { WorkspaceStorageArea.valueOf(area.orEmpty()) }
        .getOrDefault(WorkspaceStorageArea.FILES) to path.orEmpty().trim('/')

@Composable
fun WorkspaceDetailPage(
    id: String,
    initialArea: String? = null,
    initialPath: String? = null,
    openFiles: Boolean = false,
    scopeId: String? = null,
    scopeName: String? = null,
) {
    val workspaceStrings = LocalResources.current
    val navController = LocalNavController.current
    val (requestedArea, requestedPath) = workspaceDetailInitialLocation(initialArea, initialPath)
    val vm: WorkspaceDetailVM = koinViewModel(
        parameters = {
            parametersOf(WorkspaceDetailArgs(
                id = id,
                scopeId = scopeId,
                scopeName = scopeName,
                initialArea = requestedArea,
                initialPath = requestedPath,
            ))
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val remoteHostStates by vm.remoteHostStates.collectAsStateWithLifecycle()
    val remoteWorkspaceStates by vm.remoteWorkspaceStates.collectAsStateWithLifecycle()
    val remoteConnectionStates by vm.remoteConnectionStates.collectAsStateWithLifecycle()
    val repository: WorkspaceRepository = koinInject()
    val lifecycleOwner = LocalLifecycleOwner.current
    var connectionGeneration by remember(id) { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = FILES_PAGE) { if (state.workspace?.isRemote == true) 2 else 3 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, state.workspace?.remotePath, state.remoteHost?.connectionRevision,
        connectionGeneration, lifecycleOwner) {
        if (state.workspace?.isRemote != true || state.remoteHost == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val lease = runCatching { repository.retainRemoteWorkspace(id) }.getOrNull()
            try { awaitCancellation() } finally { lease?.close() }
        }
    }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showHostVerification by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }

    LaunchedEffect(id, initialArea, initialPath, openFiles, scopeId) {
        // The navigation entry's VM consumes the initial location in its constructor.
        // This effect runs again after preview/editor/terminal pages are popped; replaying
        // the route here would overwrite the directory the user has since navigated to.
        if (openFiles || initialArea != null || initialPath != null) pagerState.scrollToPage(FILES_PAGE)
    }

    BackHandler(enabled = pagerState.currentPage == FILES_PAGE && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.workspace?.name?.let { name ->
                                val scopeLabel = state.scopeId?.let { scopeId ->
                                    state.scopeName?.takeIf(String::isNotBlank) ?: scopeId.take(8)
                                }
                                if (scopeLabel == null) name else "$name · $scopeLabel"
                            } ?: stringResource(R.string.workspace_detail_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.workspace?.let { workspace ->
                            val identity = if (workspace.isRemote) {
                                workspaceStrings.getString(R.string.workspace_remote_name, state.remoteHost?.name ?: workspaceStrings.getString(R.string.workspace_host_unavailable))
                            } else workspaceStrings.getString(R.string.workspace_local_device)
                            val status = if (workspace.isRemote) {
                                when {
                                    state.remoteHost == null -> workspaceStrings.getString(R.string.workspace_host_config_unavailable)
                                    state.remoteHost?.trustedHostKeySha256 == null -> workspaceStrings.getString(R.string.workspace_host_fingerprint_pending)
                                    else -> remoteWorkspaceStatusLabel(workspaceStrings,
                                        workspace,
                                        workspace.remoteHostId?.let(remoteHostStates::get),
                                        remoteWorkspaceStates[id],
                                        remoteConnectionStates[id],
                                    )
                                }
                            } else workspace.shellStatus.toShellStatusLabel()
                            Text(
                                "$identity · $status",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == FILES_PAGE) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = workspaceStrings.getString(R.string.workspace_refresh))
                    }
                    if (state.workspace?.let { it.isRemote || it.shellStatus != WorkspaceShellStatus.DISABLED.name } == true) {
                        IconButton(
                            onClick = {
                                navController.navigate(
                                    Screen.WorkspaceTerminal(
                                        id = id,
                                        scopeId = state.scopeId,
                                        scopeName = state.scopeName,
                                    )
                                )
                            }
                        ) {
                            Icon(
                                HugeIcons.ComputerTerminal01,
                                contentDescription = if (state.workspace?.isRemote == true) workspaceStrings.getString(R.string.workspace_open_remote_terminal) else workspaceStrings.getString(R.string.workspace_open_local_terminal),
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == FILES_PAGE,
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(FILES_PAGE) } },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == SETTINGS_PAGE,
                    label = { Text(stringResource(R.string.settings)) },
                    icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    onClick = { scope.launch { pagerState.animateScrollToPage(SETTINGS_PAGE) } },
                )
                if (state.workspace?.isRemote != true) {
                    NavigationBarItem(
                        selected = pagerState.currentPage == SKILLS_PAGE,
                        label = { Text(stringResource(R.string.workspace_detail_tab_skills)) },
                        icon = { Icon(HugeIcons.Puzzle, contentDescription = null) },
                        onClick = { scope.launch { pagerState.animateScrollToPage(SKILLS_PAGE) } },
                    )
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                SETTINGS_PAGE -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    remoteHost = state.remoteHost,
                    remoteHostState = state.workspace?.remoteHostId?.let(remoteHostStates::get),
                    remoteWorkspaceState = remoteWorkspaceStates[id],
                    remoteConnectionStatus = remoteConnectionStates[id],
                    scopeId = state.scopeId,
                    scopeName = state.scopeName,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onCheckHost = { showHostVerification = true },
                    onConnect = { scope.launch {
                        try {
                            repository.connectRemoteWorkspace(id)
                            connectionGeneration++
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The connection owner publishes FAILED for the status and retry button.
                        }
                    } },
                    onDisconnect = {
                        RemoteTerminalRegistry.close(id)
                        repository.disconnectRemoteWorkspace(id)
                    },
                    onToolApprovalChange = vm::setToolApproval,
                )

                FILES_PAGE -> WorkspaceFilesPage(
                    state = state,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onRetry = vm::refresh,
                    onCheckHost = { showHostVerification = true },
                    onGoUp = vm::goUp,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> {
                                val guestPath = when (state.area) {
                                    WorkspaceStorageArea.FILES -> "/workspace/${entry.path.trimStart('/')}"
                                    WorkspaceStorageArea.LINUX -> "/${entry.path.trimStart('/')}"
                                    WorkspaceStorageArea.HOME -> "/root/${entry.path.trimStart('/')}"
                                    WorkspaceStorageArea.TEMP -> "/tmp/${entry.path.trimStart('/')}"
                                    WorkspaceStorageArea.VAR_TEMP -> "/var/tmp/${entry.path.trimStart('/')}"
                                }
                                navController.navigate(
                                    Screen.WorkspaceFilePreview(id, guestPath, state.scopeId)
                                )
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )

                SKILLS_PAGE -> WorkspaceSkillsPage(
                    skills = state.skills,
                    shellReady = state.workspace?.shellStatus == WorkspaceShellStatus.READY.name,
                    onOpenSkill = { skill ->
                        vm.openWorkspaceSkill(skill)
                        scope.launch { pagerState.animateScrollToPage(FILES_PAGE) }
                    },
                )
            }
        }
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = {
                    vm.installRootfs()
                    showInstallDialog = false
                },
            )
        }
    }

    if (showHostVerification) {
        state.remoteHost?.let { host ->
            RemoteHostVerificationDialog(
                host = host,
                discover = vm::discoverHostKey,
                trust = vm::trustHostKey,
                test = vm::testHost,
                onDismiss = { showHostVerification = false; vm.refresh() },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        MiffanConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
private fun WorkspaceSkillsPage(
    skills: List<SkillMetadata>,
    shellReady: Boolean,
    onOpenSkill: (SkillMetadata) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_skills_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_skills_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "/workspace/${SkillManager.WORKSPACE_SKILLS_PATH}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (skills.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.workspace_skills_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(skills, key = { it.skillDir.absolutePath }) { skill ->
            val available = !skill.requiresWorkspace || shellReady
            Card(
                onClick = { onOpenSkill(skill) },
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.Puzzle,
                        contentDescription = null,
                        tint = if (available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(text = skill.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (available) {
                                stringResource(R.string.workspace_skills_automatic)
                            } else {
                                stringResource(R.string.workspace_skills_requires_shell)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (available) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    remoteHost: RemoteHostEntity?,
    remoteHostState: RemoteHostRuntimeState?,
    remoteWorkspaceState: RemoteWorkspaceRuntimeState?,
    remoteConnectionStatus: RemoteConnectionStatus? = null,
    scopeId: String?,
    scopeName: String?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onCheckHost: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val workspaceStrings = LocalResources.current
    var showDetails by remember(workspace?.id) { mutableStateOf(false) }
    var showApprovals by remember(workspace?.id) { mutableStateOf(false) }
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            workspaceKindIcon(workspace?.isRemote == true),
                            contentDescription = workspaceKindLabel(workspaceStrings, workspace?.isRemote == true),
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (workspace?.isRemote == true) {
                                workspaceStrings.getString(R.string.workspace_remote_server_name, remoteHost?.name ?: workspaceStrings.getString(R.string.workspace_host_unavailable))
                            } else workspaceStrings.getString(R.string.workspace_local_device),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (workspace?.isRemote == true) {
                        Text(
                            when {
                                remoteHost == null -> workspaceStrings.getString(R.string.workspace_host_config_unavailable)
                                remoteHost.trustedHostKeySha256 == null -> workspaceStrings.getString(R.string.workspace_host_fingerprint_pending)
                                else -> remoteWorkspaceStatusLabel(workspaceStrings, workspace,
                                    remoteHostState, remoteWorkspaceState, remoteConnectionStatus)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            workspaceStrings.getString(R.string.workspace_ssh_shell_permissions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onCheckHost, enabled = remoteHost != null) {
                            Text(if (remoteHost?.trustedHostKeySha256 == null) workspaceStrings.getString(R.string.workspace_confirm_host_test) else workspaceStrings.getString(R.string.workspace_check_host_test))
                        }
                        if (remoteHost?.trustedHostKeySha256 != null) {
                            val connected = remoteConnectionStatus == RemoteConnectionStatus.CONNECTED
                            OutlinedButton(onClick = if (connected) onDisconnect else onConnect) {
                                Text(stringResource(if (connected) R.string.workspace_connection_disconnect
                                    else if (remoteConnectionStatus == RemoteConnectionStatus.FAILED)
                                        R.string.workspace_reconnect
                                    else R.string.workspace_connection_connect))
                            }
                        }
                    } else {
                        Text(
                            workspace?.shellStatus?.toShellStatusLabel() ?: stringResource(R.string.workspace_detail_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!rootfsReady) {
                            Button(
                                onClick = onInstallRootfs,
                                enabled = workspace != null && !installing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(HugeIcons.Bash, contentDescription = null)
                                Text(installButtonText, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        installProgress?.let { RootfsProgress(it) }
                    }
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) workspaceStrings.getString(R.string.workspace_collapse_details) else workspaceStrings.getString(R.string.workspace_view_connection_details))
                    }
                    if (showDetails) {
                        if (workspace?.isRemote == true) {
                            WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_login_target), remoteHost?.let { "${it.username}@${it.host}:${it.port}" } ?: workspaceStrings.getString(R.string.workspace_host_config_unavailable))
                            WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_remote_directory), workspace.remotePath.orEmpty())
                            WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_host_identity), if (remoteHost?.trustedHostKeySha256 != null) workspaceStrings.getString(R.string.workspace_fingerprint_confirmed) else workspaceStrings.getString(R.string.workspace_fingerprint_unconfirmed))
                            (remoteWorkspaceState?.lastDirectoryCheck?.reason ?: remoteHostState?.lastConnection?.reason)
                                ?.let { WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_recent_failure), it) }
                            remoteWorkspaceLastOperationLabel(workspaceStrings, remoteWorkspaceState)?.let { WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_operation_history), it) }
                            remoteHostState?.configurationReason?.let { WorkspaceInfoRow(workspaceStrings.getString(R.string.workspace_action_required), it) }
                            Text(stringResource(R.string.workspace_ssh_on_demand_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            WorkspaceInfoRow(
                                stringResource(R.string.workspace_scope),
                                if (scopeId == null) stringResource(R.string.workspace_scope_legacy)
                                else stringResource(R.string.workspace_scope_private, scopeName?.takeIf { it.isNotBlank() } ?: scopeId),
                            )
                            Text(stringResource(R.string.workspace_detail_enable_shell_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (rootfsReady) {
                                OutlinedButton(onClick = onInstallRootfs, enabled = !installing) {
                                    Text(installButtonText)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            TextButton(onClick = { showApprovals = !showApprovals }) {
                Text(if (showApprovals) workspaceStrings.getString(R.string.workspace_collapse_file_approvals) else workspaceStrings.getString(R.string.workspace_file_approval_settings))
            }
            if (showApprovals) WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val workspaceStrings = LocalResources.current
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (workspace?.isRemote == true) {
                        workspaceStrings.getString(R.string.workspace_file_shell_approval_explanation)
                    } else {
                        stringResource(R.string.workspace_detail_tool_approval_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.workspace_rootfs_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onRetry: () -> Unit,
    onCheckHost: () -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    val workspaceStrings = LocalResources.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.workspace?.isRemote == false) {
            item {
                WorkspaceAreaSelector(selected = state.area, onSelected = onSelectArea)
            }
        }

        if (state.loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    workspaceStrings.getString(R.string.workspace_loading_files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            WorkspacePathBar(
                path = if (state.workspace?.isRemote == true) {
                    state.workspace?.remotePath.orEmpty().trimEnd('/') + state.path.takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty()
                } else state.path,
                canGoUp = state.path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        state.error?.let { error ->
            item {
                ErrorCard(error, onRetry, onCheckHost.takeIf { state.workspace?.isRemote == true && state.remoteHost != null })
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val standardAreas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    val areas = if (selected in standardAreas.map { it.first }) {
        standardAreas
    } else {
        listOf(selected to selected.privateAreaLabel()) + standardAreas
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

private fun WorkspaceStorageArea.privateAreaLabel(): String = when (this) {
    WorkspaceStorageArea.HOME -> "Home"
    WorkspaceStorageArea.TEMP -> "Temp"
    WorkspaceStorageArea.VAR_TEMP -> "Var temp"
    WorkspaceStorageArea.FILES -> "Files"
    WorkspaceStorageArea.LINUX -> "Rootfs"
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_export)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Share08,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            },
                        )
                    }
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

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onCheckHost: (() -> Unit)?) {
    val workspaceStrings = LocalResources.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.onboarding_page_retry)) }
                onCheckHost?.let { action -> TextButton(onClick = action) { Text(stringResource(R.string.workspace_check_host)) } }
            }
        }
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}
