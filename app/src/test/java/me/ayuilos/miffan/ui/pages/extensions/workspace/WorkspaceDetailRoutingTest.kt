package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceDetailRoutingTest {
    @Test
    fun `ordinary detail opens files and keeps explicit local area and path deep links`() {
        assertEquals(0, FILES_PAGE)
        assertEquals(WorkspaceStorageArea.FILES to "", workspaceDetailInitialLocation(null, null))
        assertEquals(
            WorkspaceStorageArea.LINUX to "etc/nginx",
            workspaceDetailInitialLocation("LINUX", "/etc/nginx/"),
        )
        assertEquals(WorkspaceStorageArea.LINUX, resolveDetailArea(WorkspaceStorageArea.LINUX, false))
    }

    @Test
    fun `remote detail never routes local rootfs area into SSH file listing`() {
        assertEquals(WorkspaceStorageArea.FILES, resolveDetailArea(WorkspaceStorageArea.LINUX, true))
        assertEquals(
            WorkspaceStorageArea.FILES to "reports",
            workspaceDetailInitialLocation("FILES", "/reports/"),
        )
    }

    @Test
    fun `remote detail opens a full path on the computer relative to the workspace folder`() {
        val linux = WorkspaceStorageArea.LINUX
        assertEquals("out", resolveDetailPath(linux, "home/me/proj/out", true, "/home/me/proj"))
        assertEquals("", resolveDetailPath(linux, "home/me/proj", true, "/home/me/proj/"))
        // Not under the folder, local workspaces, and paths already relative stay as given.
        assertEquals("home/me/other", resolveDetailPath(linux, "home/me/other", true, "/home/me/proj"))
        assertEquals("home/me/proj/out", resolveDetailPath(linux, "home/me/proj/out", false, null))
        assertEquals("home/me/proj/out", resolveDetailPath(WorkspaceStorageArea.FILES, "home/me/proj/out", true, "/home/me/proj"))
        assertEquals("etc", resolveDetailPath(linux, "etc", true, "/"))
    }
}
