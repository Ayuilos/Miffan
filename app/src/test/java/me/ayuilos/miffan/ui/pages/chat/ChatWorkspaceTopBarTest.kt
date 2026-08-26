package me.ayuilos.miffan.ui.pages.chat

import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWorkspaceTopBarTest {
    @Test
    fun unboundAssistantHasNoWorkspaceEntry() {
        assertNull(resolveChatWorkspaceEntry(boundWorkspaceId = null, workspace = null))
    }

    @Test
    fun availableWorkspaceKeepsItsNameWithoutWarning() {
        val entry = resolveChatWorkspaceEntry(
            boundWorkspaceId = "workspace-id",
            workspace = workspace(shellStatus = WorkspaceShellStatus.READY.name),
        )

        assertEquals("workspace-id", entry?.id)
        assertEquals("Project files", entry?.name)
        assertFalse(entry?.warning ?: true)
    }

    @Test
    fun filesOnlyWorkspaceDoesNotShowWarningWhenShellIsDisabled() {
        val entry = resolveChatWorkspaceEntry(
            boundWorkspaceId = "workspace-id",
            workspace = workspace(shellStatus = WorkspaceShellStatus.DISABLED.name),
        )

        assertFalse(entry?.warning ?: true)
    }

    @Test
    fun brokenOrMissingWorkspaceRemainsVisibleWithWarning() {
        val broken = resolveChatWorkspaceEntry(
            boundWorkspaceId = "workspace-id",
            workspace = workspace(shellStatus = WorkspaceShellStatus.BROKEN.name),
        )
        val missing = resolveChatWorkspaceEntry(
            boundWorkspaceId = "workspace-id",
            workspace = null,
        )

        assertTrue(broken?.warning == true)
        assertEquals("workspace-id", missing?.id)
        assertTrue(missing?.warning == true)
    }

    @Test
    fun workspaceCwdIsConvertedToFilesAreaPath() {
        assertEquals("", workspaceCwdToFilesPath(null))
        assertEquals("", workspaceCwdToFilesPath("/workspace"))
        assertEquals("project/docs", workspaceCwdToFilesPath("/workspace/project/docs/"))
        assertEquals("project/docs", workspaceCwdToFilesPath("workspace\\project\\docs"))
    }

    @Test
    fun invalidWorkspaceCwdFallsBackToWorkspaceRoot() {
        assertEquals("", workspaceCwdToFilesPath("/tmp/project"))
        assertEquals("", workspaceCwdToFilesPath("project/../secret"))
    }

    @Test
    fun workspaceRouteOpensFilesAtConversationCwd() {
        val route = workspaceFilesRoute("workspace-id", "/workspace/project")

        assertEquals("workspace-id", route.id)
        assertEquals(WorkspaceStorageArea.FILES.name, route.area)
        assertEquals("project", route.path)
        assertTrue(route.openFiles)
    }

    private fun workspace(shellStatus: String) = WorkspaceEntity(
        id = "workspace-id",
        name = "Project files",
        root = "workspace-root",
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
