package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.LinuxHostWindows
import me.ayuilos.miffan.data.repository.RemoteHostWindow
import me.ayuilos.miffan.data.repository.SpaceMediaClosedException
import me.rerere.cuaspace.SpaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWindowIssueTest {
    @Test fun `known pauses keep their meaning and an unknown one is only a pause`() {
        for (mac in listOf(true, false)) {
            assertNull(suspendedIssue(null, mac))
            assertEquals(RemoteWindowIssue.LOCKED, suspendedIssue("locked", mac))
            assertEquals(RemoteWindowIssue.MINIMIZED, suspendedIssue("minimized", mac))
            assertEquals(RemoteWindowIssue.OTHER_DESKTOP, suspendedIssue("window_hidden", mac))
            assertEquals(RemoteWindowIssue.OTHER_DESKTOP, suspendedIssue("other_space", mac))
            assertEquals(RemoteWindowIssue.PAUSED, suspendedIssue("suspended", mac))
            assertEquals(RemoteWindowIssue.PAUSED, suspendedIssue("capture_stalled", mac))
        }
        // Only a Mac asks for permission with a dialog.
        assertEquals(RemoteWindowIssue.PERMISSION, suspendedIssue("permission_required", mac = true))
        assertEquals(RemoteWindowIssue.PERMISSION, suspendedIssue("TCC prompt", mac = true))
        assertEquals(RemoteWindowIssue.PAUSED, suspendedIssue("permission_required", mac = false))
    }

    @Test fun `failures map to the issues the page explains`() {
        for (mac in listOf(true, false)) {
            assertEquals(RemoteWindowIssue.CLOSED, windowIssueOf(SpaceMediaClosedException(4410, "window_closed"), mac))
            assertEquals(RemoteWindowIssue.NOT_AUTHORIZED, windowIssueOf(SpaceMediaClosedException(null, "not_authorized"), mac))
            assertEquals(RemoteWindowIssue.CLOSED, windowIssueOf(IllegalStateException("target NOT_FOUND"), mac))
            assertNull(windowIssueOf(SpaceMediaClosedException(null, "capture_failed"), mac))
        }
        val denied = RuntimeException("wrapped", IllegalStateException("Permission denied"))
        assertEquals(RemoteWindowIssue.PERMISSION, windowIssueOf(denied, mac = true))
        // On Linux that is no Mac dialog: the plain failure panel shows the error instead.
        assertNull(windowIssueOf(denied, mac = false))
    }

    private fun hosted(current: Boolean, hidden: Boolean = false) = RemoteHostWindow(
        SpaceWindow(id = "w", epoch = 0, app = "App", appId = "", pid = 1, title = "t", x = 0.0, y = 0.0, width = 800.0, height = 600.0,
            state = SpaceWindow.State.NORMAL, available = true, limitation = null, focused = false, zOrder = 0),
        desktop = 1, current = current, hidden = hidden,
    )

    @Test fun `a Mac window is in the background when its app shows nothing`() {
        val failed = WindowThumbnail(null, 1L, failed = true, failures = 1)
        assertTrue(windowInBackground(hosted(current = true, hidden = true), true, null, null))
        assertFalse(windowInBackground(hosted(current = true), true, null, failed))
        assertTrue(windowInBackground(hosted(current = false), true, null, failed))
        assertFalse(windowInBackground(hosted(current = false), true, null, null))
        assertFalse(windowInBackground(hosted(current = false), true, true, failed))
        assertTrue(windowInBackground(hosted(current = false), true, false, null))
    }

    @Test fun `a Linux window without a picture is never in the background`() {
        val listed = LinuxHostWindows.parse("""
            {"current":1,"windows":[{"id":"7","workspace":3,"title":"off screen"}],
             "workspaces":[{"id":1,"index":1,"active":true},{"id":3,"index":2,"active":false}]}
        """.trimIndent())
        assertFalse(listed.appsInBackground)
        val failed = WindowThumbnail(null, 1L, failed = true, failures = 3)
        assertFalse(windowInBackground(listed.windows.single(), listed.appsInBackground, null, failed))
        assertFalse(windowInBackground(listed.windows.single(), listed.appsInBackground, false, failed))
        assertFalse(LinuxHostWindows.parse("""{"error":"needs_extension","extension":"missing"}""").appsInBackground)
    }
}
