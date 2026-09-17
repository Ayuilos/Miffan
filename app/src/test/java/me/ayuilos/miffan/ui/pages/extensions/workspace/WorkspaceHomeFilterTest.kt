package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceHomeFilterTest {
    private val host = RemoteHostEntity(
        id = "host", name = "Build server", host = "build.internal", port = 22,
        username = "builder", authType = "PASSWORD", trustedHostKeySha256 = null,
        createdAt = 1, updatedAt = 1,
    )
    private val local = WorkspaceEntity(
        id = "local", name = "Notes", root = "/data/notes", createdAt = 1, updatedAt = 1,
    )
    private val remote = WorkspaceEntity(
        id = "remote", name = "Project", root = "remote:project", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = host.id, remotePath = "/srv/product",
    )

    @Test
    fun searchMatchesNameHostAndDirectoryThenAppliesTypeFilter() {
        val all = listOf(local, remote)
        assertEquals(listOf(local), filterWorkspaceList(all, listOf(host), "notes", WorkspaceListFilter.ALL))
        assertEquals(listOf(remote), filterWorkspaceList(all, listOf(host), "Build server", WorkspaceListFilter.ALL))
        assertEquals(listOf(remote), filterWorkspaceList(all, listOf(host), "build.internal", WorkspaceListFilter.ALL))
        assertEquals(listOf(remote), filterWorkspaceList(all, listOf(host), "/srv/product", WorkspaceListFilter.ALL))
        assertEquals(emptyList<WorkspaceEntity>(), filterWorkspaceList(all, listOf(host), "build.internal", WorkspaceListFilter.LOCAL))
        assertEquals(listOf(local), filterWorkspaceList(all, listOf(host), "", WorkspaceListFilter.LOCAL))
        assertEquals(listOf(remote), filterWorkspaceList(all, listOf(host), "", WorkspaceListFilter.REMOTE))
    }
}
