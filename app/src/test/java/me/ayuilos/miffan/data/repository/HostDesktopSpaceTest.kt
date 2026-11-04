package me.ayuilos.miffan.data.repository

import io.mockk.every
import io.mockk.mockk
import me.rerere.cuaspace.SpaceWindow
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDesktopSpaceTest {
    private fun ssh(stdout: String) = mockk<RemoteWorkspaceSession>().also { ssh ->
        every { ssh.platform } returns RemotePlatform.POSIX
        every { ssh.execute(any(), any(), any(), any(), any()) } returns WorkspaceCommandResult(0, stdout, "")
    }

    private fun endpointOutput(service: String, token: String, scroll: String) =
        "$service\n--miffan-host-desktop--\n$token\n--miffan-host-desktop--\n$scroll"

    @Test fun `the endpoint comes from the service's listen argument`() {
        val service = """{"args":["serve","--listen","127.0.0.1:8443"]}"""
        val endpoint = HostDesktopSpace.endpoint(ssh(endpointOutput(service, "secret", "")))
        assertEquals(8443, endpoint?.port)
        assertEquals("secret", endpoint?.token)
        // The key is absent unless the user turned natural scrolling off.
        assertEquals(true, endpoint?.naturalScroll)
        assertEquals(false, HostDesktopSpace.endpoint(ssh(endpointOutput(service, "secret", "0")))?.naturalScroll)
    }

    @Test fun `no endpoint without a listen argument, a port or a token`() {
        assertNull(HostDesktopSpace.endpoint(ssh(endpointOutput("""{"args":["serve"]}""", "secret", ""))))
        assertNull(HostDesktopSpace.endpoint(ssh(endpointOutput("""{"args":["serve","--listen"]}""", "secret", ""))))
        assertNull(HostDesktopSpace.endpoint(ssh(endpointOutput("""{"args":["--listen","127.0.0.1:99999"]}""", "secret", ""))))
        assertNull(HostDesktopSpace.endpoint(ssh(endpointOutput("""{"args":["--listen","127.0.0.1:8443"]}""", "", ""))))
        assertNull(HostDesktopSpace.endpoint(ssh("not the expected output")))
    }

    @Test fun `the endpoint never prints its token`() {
        val endpoint = HostEndpoint(8443, "secret-token", naturalScroll = true)
        assertFalse(endpoint.toString().contains("secret-token"))
        assertEquals("secret-token", endpoint.token)
    }

    @Test fun `desktops and the order of spaces parse from cua-driver and the script`() {
        val placed = HostDesktopSpace.parseDesktops("""
            {"current_space_id":3,"windows":[
              {"pid":7,"app_name":"Safari","layer":0,"z_index":5,"title":"Docs","bounds":{"x":10,"y":20,"width":800,"height":600},
               "space_ids":[3],"is_on_screen":true},
              {"pid":8,"title":"no bounds"},
              {"app_name":"no pid","bounds":{"x":0,"y":0,"width":1,"height":1}}]}
        """.trimIndent())
        assertEquals(3L, placed?.current)
        assertEquals(1, placed?.windows?.size)
        val window = placed!!.windows.single()
        assertEquals(HostDesktops.Placed(7, "Safari", 0, 5, "Docs", 10.0, 20.0, 800.0, 600.0, listOf(3L), true), window)
        assertNull(HostDesktopSpace.parseDesktops("cua-driver: command not found"))

        assertEquals(listOf(listOf(HostSpace(1, false, null), HostSpace(9, true, 42)), listOf(HostSpace(5, false, null))),
            HostDesktopSpace.parseSpaceOrder("""[[{"id":1,"type":0},{"id":9,"type":4,"pid":42}],[{"id":5,"type":0}]]"""))
        assertNull(HostDesktopSpace.parseSpaceOrder("[]"))
        assertNull(HostDesktopSpace.parseSpaceOrder("error"))
    }

    private fun placed(pid: Int, title: String, x: Double = 0.0, z: Int = 0, app: String = "App", width: Double = 800.0, height: Double = 600.0) =
        HostDesktops.Placed(pid, app, layer = 0, z = z, title = title, x = x, y = 0.0, width = width, height = height,
            spaces = listOf(1L), onScreen = true)

    private fun window(pid: Int, title: String, x: Double = 0.0) = SpaceWindow(id = "w", epoch = 1, app = "App", appId = "",
        pid = pid, title = title, x = x, y = 0.0, width = 800.0, height = 600.0, state = SpaceWindow.State.NORMAL,
        available = true, limitation = null, focused = false, zOrder = 0)

    @Test fun `find needs the same title and takes the closest frame`() {
        val desktops = HostDesktops(1, listOf(placed(7, "A", x = 0.0), placed(7, "A", x = 300.0), placed(7, "B", x = 0.0)))
        assertEquals(300.0, desktops.find(7, "A", 290.0, 0.0, 800.0, 600.0)?.x)
        // A title that changed between the two lists (a terminal, a player) finds nothing.
        assertNull(desktops.find(7, "A (1)", 0.0, 0.0, 800.0, 600.0))
        assertNull(desktops.find(8, "A", 0.0, 0.0, 800.0, 600.0))
    }

    @Test fun `only the shown window itself in front counts`() {
        val shown = window(7, "Docs")
        assertTrue(HostDesktops(1, listOf(placed(7, "Docs", z = 2), placed(7, "Mail", x = 400.0, z = 1))).frontIs(shown))
        // Another window of the same app was brought to the front.
        assertFalse(HostDesktops(1, listOf(placed(7, "Docs", z = 1), placed(7, "Mail", x = 400.0, z = 2))).frontIs(shown))
        // Another app in front.
        assertFalse(HostDesktops(1, listOf(placed(7, "Docs", z = 1), placed(9, "Other", z = 2))).frontIs(shown))
        // Its title changed since it was picked: matched by its frame instead.
        assertTrue(HostDesktops(1, listOf(placed(7, "Docs — edited", z = 2), placed(7, "Mail", x = 400.0, z = 1))).frontIs(shown))
        assertFalse(HostDesktops(1, listOf(placed(7, "Docs — edited", z = 1), placed(7, "Mail", x = 400.0, z = 2))).frontIs(shown))
        // Overlays, small popups and an empty desktop.
        assertTrue(HostDesktops(1, listOf(placed(7, "Docs", z = 1), placed(9, "", z = 3, height = 40.0))).frontIs(shown))
        assertTrue(HostDesktops(1, listOf(placed(7, "Docs", z = 1), placed(9, "", z = 3, app = "Cua Driver"))).frontIs(shown))
        assertFalse(HostDesktops(1, emptyList()).frontIs(shown))
    }

    @Test fun `two look-alike windows stacked on each other are told apart by macOS window number`() {
        val shown = window(7, "zsh")
        fun twin(x: Double, z: Int, id: Long, title: String = "zsh") = placed(7, title, x = x, z = z).copy(windowId = id)
        // Its number known: the front window must be that very window, however alike the other one is.
        val stacked = HostDesktops(1, listOf(twin(10.0, 2, 200), twin(0.0, 1, 100)))
        assertFalse(stacked.frontIs(shown, windowId = 100))
        assertTrue(stacked.frontIs(shown, windowId = 200))
        // Found by frame when only one window of the app is near it, or only one of those has its title.
        assertEquals(100L, HostDesktops(1, listOf(twin(0.0, 1, 100), twin(400.0, 2, 200))).windowIdOf(shown))
        assertEquals(100L, HostDesktops(1, listOf(twin(0.0, 1, 100), twin(10.0, 2, 200, "vim"))).windowIdOf(shown))
        // Look-alikes, or nothing near: not by frame.
        assertNull(stacked.windowIdOf(shown))
        assertNull(HostDesktops(1, listOf(twin(400.0, 1, 100))).windowIdOf(shown))

        // By order: cua-spacesd's z order and cua-driver's (every window) count the same way.
        val listed = listOf(shown.copy(zOrder = 1001), shown.copy(id = "b", x = 10.0, zOrder = 994))
        val all = HostDesktops(1, listOf(twin(10.0, 994, 200), twin(0.0, 1001, 100)))
        assertEquals(100L, windowIdByOrder(listed, all, shown))
        assertEquals(200L, windowIdByOrder(listed, all, shown.copy(id = "b")))
        // The order changed in between, or the window is gone: no answer rather than a guess.
        assertNull(windowIdByOrder(listed, HostDesktops(1, listOf(twin(10.0, 1001, 200), twin(0.0, 1002, 100))), shown))
        assertNull(windowIdByOrder(listed.drop(1), all, shown))
    }

    @Test fun `a sandboxed app's open panel over the window counts as the window`() {
        val shown = window(7, "Docs")
        val panel = placed(99, "Open", x = 100.0, z = 3, app = "Open and Save Panel Service", width = 500.0, height = 400.0)
        assertTrue(HostDesktops(1, listOf(placed(7, "Docs", z = 1), panel)).frontIs(shown))
        // Not over the window: someone else's panel.
        assertFalse(HostDesktops(1, listOf(placed(7, "Docs", z = 1), panel.copy(x = 600.0))).frontIs(shown))
        // Another app's small window over it is still another app.
        assertFalse(HostDesktops(1, listOf(placed(7, "Docs", z = 1), panel.copy(app = "Finder"))).frontIs(shown))
    }
}
