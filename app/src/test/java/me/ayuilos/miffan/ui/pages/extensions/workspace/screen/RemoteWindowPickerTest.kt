package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.HostSpace
import me.ayuilos.miffan.data.repository.LinuxHostWindows
import me.ayuilos.miffan.data.repository.RemoteHostDesktop
import me.ayuilos.miffan.data.repository.RemoteHostWindow
import me.ayuilos.miffan.data.repository.desktopLabels
import me.ayuilos.miffan.testutils.workspaceTestResources
import me.rerere.cuaspace.SpaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWindowPickerTest {
    private fun window(id: String, pid: Int, app: String) = SpaceWindow(
        id = id, epoch = 1, app = app, appId = "", pid = pid, title = "", x = 0.0, y = 0.0, width = 800.0, height = 600.0,
        state = SpaceWindow.State.NORMAL, available = true, limitation = null, focused = false, zOrder = 0,
    )

    private fun choices(windows: List<RemoteHostWindow>) = windows.map {
        RemoteWindowChoice(it.window, null, it.desktop, it.current, background = false)
    }

    /** Every key the picker's LazyColumn gets: one per group header, one per window. */
    private fun keys(list: RemoteWindowList) = remoteWindowGroups(list).flatMap { (desktop, members) ->
        listOf(remoteWindowHeaderKey(desktop)) + members.map { it.window.id }
    }

    private fun assertUnique(keys: List<String>) = assertEquals(keys.toString(), keys.size, keys.toSet().size)

    @Test fun `two full-screen windows of one app share a name but not a key`() {
        // Two Safari windows each in full screen, neither in front, plus one whose app name is unknown twice.
        val hosted = listOf(
            RemoteHostWindow(window("a", 10, "Safari"), desktop = 5, current = false, hidden = false),
            RemoteHostWindow(window("b", 10, "Safari"), desktop = 6, current = false, hidden = false),
            RemoteHostWindow(window("c", 11, ""), desktop = 7, current = false, hidden = false),
            RemoteHostWindow(window("d", 12, ""), desktop = 8, current = false, hidden = false),
            RemoteHostWindow(window("e", 13, "Notes"), desktop = 1, current = true, hidden = false),
            RemoteHostWindow(window("f", 14, "Mail"), desktop = null, current = false, hidden = false),
        )
        val order = listOf(listOf(HostSpace(1, false, null), HostSpace(5, true, 10), HostSpace(6, true, 10),
            HostSpace(7, true, 11), HostSpace(8, true, 12)))
        val desktops = desktopLabels(order, current = 1, windows = hosted)
        val list = RemoteWindowList(choices(hosted), desktops)
        for (locale in listOf("values", "values-zh")) {
            val labels = desktops.map { remoteHostDesktopLabel(workspaceTestResources(locale), it) }
            // The names collide (which is why they cannot be keys)...
            assertEquals(labels[1], labels[2])
            assertEquals(labels[3], labels[4])
        }
        // ...the keys do not.
        assertUnique(keys(list))
        assertEquals(listOf(1L, 5L, 6L, 7L, 8L, null), remoteWindowGroups(list).map { it.first?.id })
    }

    @Test fun `desktops without an order or a known display keep unique keys`() {
        val hosted = listOf(
            RemoteHostWindow(window("a", 1, "A"), desktop = 3, current = false, hidden = false),
            RemoteHostWindow(window("b", 2, "B"), desktop = 9, current = false, hidden = false),
        )
        assertUnique(keys(RemoteWindowList(choices(hosted), desktopLabels(null, current = null, windows = hosted))))
        // niri: two outputs listed, both workspaces on an output that is not among them, so both read "Desktop 1".
        val listed = LinuxHostWindows.parse("""
            {"current":1,"outputs":["DP-1","HDMI-A-1"],
             "windows":[{"id":"7","workspace":3,"title":"x"},{"id":"9","workspace":4,"title":"y"}],
             "workspaces":[{"id":3,"index":1,"output":"DP-2","active":false},{"id":4,"index":1,"output":"DP-3","active":false}]}
        """.trimIndent())
        val labels = listed.desktops.map { remoteHostDesktopLabel(workspaceTestResources("values"), it) }
        assertEquals(labels[0], labels[1])
        assertUnique(keys(RemoteWindowList(choices(listed.windows), listed.desktops)))
    }

    @Test fun `a desktop listed twice shows its windows once`() {
        val hosted = listOf(RemoteHostWindow(window("a", 1, "A"), desktop = 3, current = true, hidden = false))
        val desktop = RemoteHostDesktop(3, current = true, number = 1)
        val list = RemoteWindowList(choices(hosted), listOf(desktop, desktop))
        assertUnique(keys(list))
        assertTrue(remoteWindowGroups(list).single().second.single().window.id == "a")
    }
}
