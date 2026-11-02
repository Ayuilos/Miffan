package me.ayuilos.miffan.data.repository

import me.rerere.workspace.screen.RfbKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotInputTest {
    private fun mac() = SnapshotInput(macos = true, windows = false)

    @Test fun `a press and release in place is one click where it was pressed`() {
        val input = mac()
        assertTrue(input.pointer(100, 200, 0).isEmpty())
        assertTrue(input.pointer(100, 200, 1).isEmpty())
        assertEquals(listOf(SnapshotAction.Click(100, 200)), input.pointer(104, 203, 0))
    }

    @Test fun `a press that travels is a drag`() {
        val input = mac()
        input.pointer(10, 10, 1)
        input.pointer(60, 40, 1)
        assertEquals(listOf(SnapshotAction.Drag(10, 10, 300, 220)), input.pointer(300, 220, 0))
    }

    @Test fun `pointer moves alone send nothing`() {
        val input = mac()
        assertTrue(input.pointer(1, 1, 0).isEmpty())
        assertTrue(input.pointer(500, 400, 0).isEmpty())
    }

    @Test fun `right and middle buttons click on release`() {
        val input = mac()
        input.pointer(5, 6, 4)
        assertEquals(listOf(SnapshotAction.Click(5, 6, "right")), input.pointer(5, 6, 0))
        input.pointer(7, 8, 2)
        assertEquals(listOf(SnapshotAction.Click(7, 8, "middle")), input.pointer(7, 8, 0))
    }

    @Test fun `wheel bits scroll at the pointer once per press`() {
        val input = mac()
        assertEquals(listOf(SnapshotAction.Scroll(50, 60, "down")), input.pointer(50, 60, 16))
        assertTrue(input.pointer(50, 60, 16).isEmpty())
        assertTrue(input.pointer(50, 60, 0).isEmpty())
        assertEquals(listOf(SnapshotAction.Scroll(50, 60, "up")), input.pointer(50, 60, 8))
        input.pointer(50, 60, 0)
        assertEquals(listOf(SnapshotAction.Scroll(50, 60, "right")), input.pointer(50, 60, 64))
    }

    @Test fun `queued clicks on one spot become a double click and wheel steps one scroll`() {
        val folded = SnapshotInput.coalesce(listOf(
            SnapshotAction.Click(10, 10), SnapshotAction.Click(12, 11),
            SnapshotAction.Scroll(0, 0, "down"), SnapshotAction.Scroll(0, 0, "down"), SnapshotAction.Scroll(0, 0, "down"),
            SnapshotAction.Scroll(0, 0, "up"),
            SnapshotAction.Click(400, 400),
        ))
        assertEquals(listOf(
            SnapshotAction.Click(10, 10, count = 2),
            SnapshotAction.Scroll(0, 0, "down", 3),
            SnapshotAction.Scroll(0, 0, "up"),
            SnapshotAction.Click(400, 400),
        ), folded)
    }

    @Test fun `clicks apart or with another button stay separate and a click never exceeds a triple`() {
        val folded = SnapshotInput.coalesce(List(4) { SnapshotAction.Click(1, 1) } +
            SnapshotAction.Click(1, 1, "right") + SnapshotAction.Click(100, 1))
        assertEquals(listOf(
            SnapshotAction.Click(1, 1, count = 3), SnapshotAction.Click(1, 1),
            SnapshotAction.Click(1, 1, "right"), SnapshotAction.Click(100, 1),
        ), folded)
    }

    @Test fun `keys with held modifiers are hotkeys named for the platform`() {
        val input = mac()
        assertNull(input.key(RfbKeys.SUPER_L, true))
        assertEquals(SnapshotAction.Key("c", listOf("cmd")), input.key('C'.code, true))
        assertNull(input.key('C'.code, false))
        assertNull(input.key(RfbKeys.SUPER_L, false))
        assertEquals(SnapshotAction.Key("return"), input.key(RfbKeys.RETURN, true))

        val windows = SnapshotInput(macos = false, windows = true)
        windows.key(RfbKeys.CONTROL_L, true)
        windows.key(RfbKeys.ALT_L, true)
        assertEquals(SnapshotAction.Key("delete", listOf("ctrl", "alt")), windows.key(RfbKeys.DELETE, true))
        windows.key(RfbKeys.CONTROL_L, false)
        windows.key(RfbKeys.ALT_L, false)
        windows.key(RfbKeys.SUPER_L, true)
        assertEquals(SnapshotAction.Key("left", listOf("win")), windows.key(RfbKeys.LEFT, true))

        val linux = SnapshotInput(macos = false, windows = false)
        linux.key(RfbKeys.ALT_L, true)
        assertEquals(SnapshotAction.Key("f4", listOf("alt")), linux.key(RfbKeys.F1 + 3, true))
    }

    @Test fun `key names follow cua-driver's vocabulary`() {
        val input = mac()
        assertEquals("backspace", (input.key(RfbKeys.BACKSPACE, true) as SnapshotAction.Key).name)
        assertEquals("forward_delete", (input.key(RfbKeys.DELETE, true) as SnapshotAction.Key).name)
        assertEquals("space", (input.key(0x20, true) as SnapshotAction.Key).name)
        assertEquals("pagedown", (input.key(RfbKeys.PAGE_DOWN, true) as SnapshotAction.Key).name)
        assertEquals("f12", (input.key(RfbKeys.F1 + 11, true) as SnapshotAction.Key).name)
        assertNull(input.key(0x4E00, true))
    }
}
