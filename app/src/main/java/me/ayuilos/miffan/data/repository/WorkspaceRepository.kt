package me.ayuilos.miffan.data.repository

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.workspace.RootfsCatalog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceScope
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val networkBroker: WorkspaceNetworkBroker,
    private val settingsStore: SettingsStore,
) {
    private val localWorkspaceCreator = LocalWorkspaceCreator(dao, manager)

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(name: String): WorkspaceEntity = localWorkspaceCreator.create(name)

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                val source = RootfsCatalog.forAndroidAbis(Build.SUPPORTED_ABIS.toList())
                rootfsInstaller.install(workspace.root, source, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}", e)
            // Installer swaps atomically and restores the previous Rootfs on failure. Preserve the
            // prior state when that rollback left a usable installation in place.
            if (withContext(Dispatchers.IO) { manager.hasRootfs(workspace.root) }) {
                restoreShellState(workspace)
            } else {
                updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            }
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.listFiles(workspace.root, path, area, scope)
    }

    suspend fun readText(
        id: String,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.readText(workspace.root, path, scope = scope)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.writeText(workspace.root, path, text, overwrite, scope = scope)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path, scope = scope)
            WorkspaceStorageArea.LINUX,
            WorkspaceStorageArea.HOME,
            WorkspaceStorageArea.TEMP,
            WorkspaceStorageArea.VAR_TEMP,
                -> {
                val size = manager.fileSize(workspace.root, path, area, scope)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out, scope = scope)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    /** Read a UTF-8 file by its absolute Rootfs path for the unified artifact preview screen. */
    suspend fun readRootfsTextForPreview(
        id: String,
        path: String,
        scopeId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        val size = manager.rootfsFileSize(workspace.root, path, scope)
        require(size <= MAX_PREVIEW_BYTES) {
            "文件过大, 无法在应用内预览 (${size} bytes)"
        }
        ByteArrayOutputStream(size.toInt()).use { out ->
            manager.exportRootfsFile(workspace.root, path, out, scope = scope)
            out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream, scope)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        scopeId: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area, WorkspaceScope.fromNullableId(scopeId))
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(
            workspace.root,
            path,
            area,
            outputStream,
            scope = WorkspaceScope.fromNullableId(scopeId),
        )
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
        scopeId: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.rootfsFileSize(workspace.root, path, scope)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.exportRootfsFile(workspace.root, path, outputStream, scope = scope)
    }

    /** Export a user-selected artifact without the smaller AI tool read limit. */
    suspend fun exportRootfsArtifact(
        id: String,
        path: String,
        outputStream: OutputStream,
        scopeId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.exportRootfsFile(
            workspace.root,
            path,
            outputStream,
            maxBytes = Long.MAX_VALUE,
            scope = scope,
        )
    }

    /** Writes a Rootfs guest path without invoking a shell or following symbolic links. */
    suspend fun writeRootfsText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        manager.writeRootfsText(workspace.root, path, text, overwrite, scope = scope)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
        scopeId: String? = null,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(
                workspace.root,
                path,
                recursive,
                area,
                WorkspaceScope.fromNullableId(scopeId),
            )
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(
            workspace.root,
            source,
            target,
            overwrite,
            WorkspaceScope.fromNullableId(scopeId),
        )
    }

    suspend fun fetchUrl(
        id: String,
        url: String,
        destinationPath: String,
        scopeId: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val guestPath = me.rerere.workspace.GuestPath.parse(destinationPath, "destination_path")
        require(guestPath.isWithin(WorkspaceManager.ROOTFS_WORKSPACE_PATH) &&
            guestPath != WorkspaceManager.ROOTFS_WORKSPACE_PATH
        ) { "Network downloads must target a file below /workspace" }
        val relative = guestPath.relativeTo(WorkspaceManager.ROOTFS_WORKSPACE_PATH)
        val parent = relative.substringBeforeLast('/', "")
        val fileName = relative.substringAfterLast('/')
        val scope = WorkspaceScope.fromNullableId(scopeId)
        manager.ensureScope(workspace.root, scope)
        networkBroker.fetch(url) { input ->
            manager.importFile(
                root = workspace.root,
                destinationPath = parent,
                fileName = fileName,
                inputStream = input,
                scope = scope,
            )
        }
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        scopeId: String? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            val scope = WorkspaceScope.fromNullableId(scopeId)
            manager.ensureScope(workspace.root, scope)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin, scope)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null, workspaceScopeId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
