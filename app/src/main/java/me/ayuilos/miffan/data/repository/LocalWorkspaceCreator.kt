package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import kotlin.uuid.Uuid

/** Creates the app-owned workspace record without requiring the optional Linux executor. */
internal class LocalWorkspaceCreator(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val idProvider: () -> String = { Uuid.random().toString() },
    private val timeProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun create(name: String): WorkspaceEntity {
        val finalName = name.trim().ifBlank { "Workspace" }
        require(dao.getAll().none { it.name.trim() == finalName }) {
            "Workspace name already exists: $finalName"
        }

        val id = idProvider()
        val now = timeProvider()
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )

        withContext(Dispatchers.IO) {
            check(!manager.workspaceDir(workspace.root).exists()) {
                "Workspace directory already exists: ${workspace.root}"
            }
            manager.ensureWorkspace(workspace.root)
        }
        try {
            dao.upsert(workspace)
        } catch (error: Throwable) {
            // The generated root is private to this attempted record. Do not leave an orphan when
            // persistence fails after the directory was created.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { manager.deleteWorkspace(workspace.root) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        }
        return workspace
    }
}
