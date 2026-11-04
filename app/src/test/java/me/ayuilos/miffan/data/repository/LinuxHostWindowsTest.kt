package me.ayuilos.miffan.data.repository

import me.rerere.cuaspace.SpaceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxHostWindowsTest {
    @Test fun `input becomes the helper's lines`() {
        assertEquals("move 0.25000 1.00000\n", linuxWindowLine(SpaceInput.Pointer("move", null, 0.25, 1.4)))
        assertEquals("down left 0.50000 0.00000\n", linuxWindowLine(SpaceInput.Pointer("down", "left", 0.5, -0.2)))
        assertNull(linuxWindowLine(SpaceInput.Pointer("down", "back", 0.5, 0.5)))
        assertEquals("scroll 0.10000 0.20000 0.0 -100.0\n", linuxWindowLine(SpaceInput.Scroll(0.1, 0.2, 0.0, -100.0)))
        assertEquals("key arrowleft 1\n", linuxWindowLine(SpaceInput.Key("arrowleft", true)))
        assertNull(linuxWindowLine(SpaceInput.Key("a b", true)))
        // Text of any language goes as UTF-8 base64, so it stays one line.
        assertEquals("text 5L2g5aW9IGhp\n", linuxWindowLine(SpaceInput.Text("你好 hi")))
        assertNull(linuxWindowLine(SpaceInput.Text("")))
    }

    @Test fun `listed niri windows group by workspace, current first`() {
        val listed = LinuxHostWindows.parse("""
            {"desktop":"niri","current":1,"outputs":["DP-1","HDMI-A-1"],
             "windows":[
              {"id":"7","pid":42,"app":"Notes","app_id":"org.example.Notes","title":"Plan","workspace":3,"focused":false,"width":800,"height":600},
              {"id":"9","pid":43,"app":"","app_id":"kitty","title":"shell","workspace":1,"focused":true,"width":400,"height":300}],
             "workspaces":[
              {"id":1,"index":1,"output":"DP-1","name":null,"active":true},
              {"id":3,"index":2,"output":"HDMI-A-1","name":null,"active":true},
              {"id":4,"index":1,"output":"HDMI-A-1","name":null,"active":false}]}
        """.trimIndent())
        assertEquals(listOf("9", "7"), listed.windows.map { it.window.id })
        val shell = listed.windows.first()
        assertEquals("kitty", shell.window.app)
        assertEquals(1L, shell.desktop)
        assertEquals(true, shell.current)
        assertEquals(400.0, shell.window.width, 0.0)
        // Only workspaces holding windows, the current one first; displays count from 1 when there are several.
        assertEquals(listOf(RemoteHostDesktop(1, current = true, number = 1, display = 1),
            RemoteHostDesktop(3, current = false, number = 2, display = 2)), listed.desktops)
    }

    @Test fun `KDE windows keep their uuid and show minimized`() {
        val listed = LinuxHostWindows.parse("""
            {"desktop":"kde","current":1,"outputs":[],
             "windows":[{"id":"708d5d37-b7d0-4508-a139-8c42f605b068","pid":9,"app":"Konsole","app_id":"org.kde.konsole",
               "title":"zsh","workspace":1,"focused":false,"width":1022,"height":632,"minimized":true,"visible":false}],
             "workspaces":[{"id":1,"index":1,"output":null,"name":"Desktop 1","active":true}]}
        """.trimIndent())
        val window = listed.windows.single().window
        assertEquals("708d5d37-b7d0-4508-a139-8c42f605b068", window.id)
        assertEquals(me.rerere.cuaspace.SpaceWindow.State.MINIMIZED, window.state)
        assertEquals("\"\$HOME/.miffan/bin/miffan\" window serve 708d5d37-b7d0-4508-a139-8c42f605b068 1600 30 5000",
            LinuxHostWindows.command("serve", window.id, "1600", "30", "5000"))
    }

    @Test fun `commands take fixed operations and numbers only`() {
        assertEquals("\"\$HOME/.miffan/bin/miffan\" window serve 7 1600 30 5000",
            LinuxHostWindows.command("serve", "7", "1600", "30", "5000"))
        assertThrows(IllegalArgumentException::class.java) { LinuxHostWindows.command("serve", "7; rm -rf ~") }
        assertThrows(IllegalArgumentException::class.java) { LinuxHostWindows.command("exec") }
    }
}
