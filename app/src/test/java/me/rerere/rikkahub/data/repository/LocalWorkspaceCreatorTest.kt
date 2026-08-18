package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RejectingWorkspaceShellRunner
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkspaceCreatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createPersistsWorkspaceWithoutLinuxExecutor() = runBlocking {
        val dao = FakeWorkspaceDao()
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder("workspaces"),
            shellRunner = RejectingWorkspaceShellRunner(),
        )
        val creator = LocalWorkspaceCreator(
            dao = dao,
            manager = manager,
            idProvider = { WORKSPACE_ID },
            timeProvider = { 1234L },
        )

        val workspace = creator.create("  Project  ")

        assertEquals("Project", workspace.name)
        assertEquals(workspace, dao.getById(WORKSPACE_ID))
        assertTrue(manager.filesDir(WORKSPACE_ID).isDirectory)
        assertTrue(manager.linuxDir(WORKSPACE_ID).isDirectory)
        assertTrue(manager.tempDir(WORKSPACE_ID).isDirectory)
    }

    @Test
    fun persistenceFailureRollsBackNewDirectory() = runBlocking {
        val dao = FakeWorkspaceDao(failUpsert = true)
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder("rollback-workspaces"),
            shellRunner = RejectingWorkspaceShellRunner(),
        )
        val creator = LocalWorkspaceCreator(
            dao = dao,
            manager = manager,
            idProvider = { WORKSPACE_ID },
        )

        val result = runCatching { creator.create("Project") }

        assertTrue(result.isFailure)
        assertFalse(manager.workspaceDir(WORKSPACE_ID).exists())
    }

    private class FakeWorkspaceDao(
        private val failUpsert: Boolean = false,
    ) : WorkspaceDAO {
        private val workspaces = MutableStateFlow<List<WorkspaceEntity>>(emptyList())

        override fun listFlow(): Flow<List<WorkspaceEntity>> = workspaces

        override suspend fun getById(id: String): WorkspaceEntity? =
            workspaces.value.firstOrNull { it.id == id }

        override suspend fun upsert(workspace: WorkspaceEntity) {
            if (failUpsert) error("database unavailable")
            workspaces.value = workspaces.value.filterNot { it.id == workspace.id } + workspace
        }

        override suspend fun getAll(): List<WorkspaceEntity> = workspaces.value

        override suspend fun updateShellStatus(
            id: String,
            shellStatus: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun deleteById(id: String): Int {
            val before = workspaces.value.size
            workspaces.value = workspaces.value.filterNot { it.id == id }
            return before - workspaces.value.size
        }
    }

    private companion object {
        private const val WORKSPACE_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
