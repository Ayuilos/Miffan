package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.res.Resources
import me.ayuilos.miffan.utils.workspaceErrorMessage
import me.ayuilos.miffan.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.files.SkillMetadata
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.RemoteHostKey

class WorkspaceDetailVM(
    private val args: WorkspaceDetailArgs,
    private val repository: WorkspaceRepository,
    private val skillManager: SkillManager,
    private val workspaceStrings: Resources,
) : ViewModel() {
    val remoteHostStates = repository.remoteHostStates
    val remoteWorkspaceStates = repository.remoteWorkspaceStates
    private val id = args.id
    private val scopeId = args.scopeId
    private var filesLoadJob: Job? = null
    private var filesLoadGeneration = 0L

    private val _state = MutableStateFlow(WorkspaceDetailState(
        area = args.initialArea,
        path = args.initialPath.trim('/'),
        scopeId = args.scopeId,
        scopeName = args.scopeName,
    ))
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        if (args.loadFilesInitially) refresh() else reloadMetadataOnly()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        if (state.value.workspace?.isRemote == true && area != WorkspaceStorageArea.FILES) return
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun navigateTo(area: WorkspaceStorageArea, path: String) {
        val targetArea = resolveDetailArea(area, state.value.workspace?.isRemote == true)
        val targetPath = path.trim('/')
        if (state.value.area == targetArea && state.value.path == targetPath) return
        _state.update {
            it.copy(
                area = targetArea,
                path = targetPath,
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        val generation = ++filesLoadGeneration
        val area = state.value.area
        val path = state.value.path
        filesLoadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        filesLoadJob = viewModelScope.launch {
            try {
                val metadata = fetchWorkspaceMetadata()
                currentCoroutineContext().ensureActive()
                if (generation != filesLoadGeneration) return@launch
                val effectiveArea = resolveDetailArea(area, metadata.workspace?.isRemote == true)
                _state.update { current ->
                    if (generation != filesLoadGeneration || current.area != area || current.path != path) current
                    else current.withMetadata(metadata).copy(area = effectiveArea)
                }
                if (generation != filesLoadGeneration) return@launch
                val entries = repository.listFiles(
                    id = id,
                    area = effectiveArea,
                    path = path,
                    scopeId = scopeId,
                )
                currentCoroutineContext().ensureActive()
                _state.update { current ->
                    if (generation != filesLoadGeneration || current.area != effectiveArea || current.path != path) current
                    else current.copy(entries = entries, loading = false, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                _state.update { current ->
                    if (generation != filesLoadGeneration || current.path != path) current
                    else current.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_load_files_failed),
                    )
                }
            }
        }
    }

    fun openWorkspaceSkill(skill: SkillMetadata) {
        if (skill.workspaceId != id || skill.workspaceScopeId != scopeId) return
        _state.update {
            it.copy(
                area = WorkspaceStorageArea.FILES,
                path = "${SkillManager.WORKSPACE_SKILLS_PATH}/${skill.skillDir.name}",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                    scopeId = scopeId,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.skill_detail_page_delete_failed)) }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                    scopeId = scopeId,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.text_area_import_failed)) }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                    scopeId = scopeId,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_export_files_failed)) }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                        scopeId = scopeId,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_export_files_failed)) }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            try {
                repository.setToolApproval(workspace.id, toolName, needsApproval)
                refreshMetadata()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_update_approval_failed)) }
            }
        }
    }

    fun discoverHostKey(onResult: (Result<RemoteHostKey>) -> Unit) {
        val hostId = state.value.remoteHost?.id ?: return onResult(Result.failure(IllegalStateException(workspaceStrings.getString(R.string.workspace_host_config_unavailable))))
        runRemoteCheck({ repository.discoverHostKey(hostId) }, onResult)
    }

    fun trustHostKey(fingerprint: String, onResult: (Result<Boolean>) -> Unit) {
        val hostId = state.value.remoteHost?.id ?: return onResult(Result.failure(IllegalStateException(workspaceStrings.getString(R.string.workspace_host_config_unavailable))))
        runRemoteCheck({ repository.trustHostKey(hostId, fingerprint) }, onResult)
    }

    fun testHost(onResult: (Result<Boolean>) -> Unit) {
        val hostId = state.value.remoteHost?.id ?: return onResult(Result.failure(IllegalStateException(workspaceStrings.getString(R.string.workspace_host_config_unavailable))))
        runRemoteCheck({ repository.testHost(hostId) }, onResult)
    }

    private fun <T> runRemoteCheck(block: suspend () -> T, onResult: (Result<T>) -> Unit) {
        viewModelScope.launch {
            val result = try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            onResult(result)
            if (result.isSuccess) refreshMetadata()
        }
    }

    fun installRootfs() {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            if (workspace.isRemote) return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id) { progress ->
                    _installProgress.value = progress
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_detail_rootfs_install_failed)
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed, scopeId = scopeId)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_command_execution_failed)),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private suspend fun fetchWorkspaceMetadata(): WorkspaceDetailMetadata {
        val workspace = repository.getById(id)
        val remoteHost = workspace?.remoteHostId?.let { repository.getHostById(it) }
        val skills = if (workspace == null || workspace.isRemote) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                skillManager.listWorkspaceSkills(
                    workspaceId = workspace.id,
                    workspaceRoot = workspace.root,
                    scopeId = scopeId,
                )
            }
        }
        currentCoroutineContext().ensureActive()
        return WorkspaceDetailMetadata(workspace, remoteHost, skills)
    }

    private suspend fun refreshMetadata() {
        try {
            val metadata = fetchWorkspaceMetadata()
            currentCoroutineContext().ensureActive()
            _state.update { it.withMetadata(metadata) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_load_info_failed)) }
        }
    }

    fun reloadMetadataOnly() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val metadata = fetchWorkspaceMetadata()
                currentCoroutineContext().ensureActive()
                _state.update { it.withMetadata(metadata).copy(loading = false, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(loading = false, error = error.workspaceErrorMessage(workspaceStrings) ?: workspaceStrings.getString(R.string.workspace_load_info_failed)) }
            }
        }
    }
}

private data class WorkspaceDetailMetadata(
    val workspace: WorkspaceEntity?,
    val remoteHost: RemoteHostEntity?,
    val skills: List<SkillMetadata>,
)

private fun WorkspaceDetailState.withMetadata(metadata: WorkspaceDetailMetadata): WorkspaceDetailState = copy(
    workspace = metadata.workspace,
    remoteHost = metadata.remoteHost,
    skills = metadata.skills,
)

internal fun resolveDetailArea(requested: WorkspaceStorageArea, isRemote: Boolean): WorkspaceStorageArea =
    if (isRemote) WorkspaceStorageArea.FILES else requested

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val remoteHost: RemoteHostEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val skills: List<SkillMetadata> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val scopeId: String? = null,
    val scopeName: String? = null,
)

data class WorkspaceDetailArgs(
    val id: String,
    val scopeId: String? = null,
    val scopeName: String? = null,
    val initialArea: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val initialPath: String = "",
    val loadFilesInitially: Boolean = true,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
