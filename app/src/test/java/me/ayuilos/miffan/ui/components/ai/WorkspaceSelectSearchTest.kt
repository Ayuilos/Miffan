package me.ayuilos.miffan.ui.components.ai

import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSelectSearchTest {
    private val remote = WorkspaceEntity(
        id = "remote", name = "Project Alpha", root = "remote:remote", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/reports",
    )
    private val host = RemoteHostEntity(
        id = "host", name = "Build server", host = "node.tailnet", port = 22,
        username = "builder", authType = "PASSWORD", trustedHostKeySha256 = null,
        createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `search locates remote workspaces by hidden host and directory details`() {
        assertTrue(workspaceMatchesSelectionQuery(remote, host, "  BUILD  "))
        assertTrue(workspaceMatchesSelectionQuery(remote, host, "tailnet"))
        assertTrue(workspaceMatchesSelectionQuery(remote, host, "reports"))
        assertFalse(workspaceMatchesSelectionQuery(remote, host, "unrelated"))
    }

    @Test
    fun `local search does not require host metadata`() {
        val local = WorkspaceEntity(
            id = "local", name = "Notes", root = "/local", createdAt = 1, updatedAt = 1,
        )
        assertTrue(workspaceMatchesSelectionQuery(local, null, "notes"))
        assertFalse(workspaceMatchesSelectionQuery(local, null, "server"))
    }
}
