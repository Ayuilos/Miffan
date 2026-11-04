package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.ui.geometry.Offset
import me.ayuilos.miffan.data.repository.area
import me.rerere.cuaspace.SpaceFrameArea
import me.rerere.workspace.screen.RemoteFrameArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteScreenReframeTest {
    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.5f)
        assertEquals(expected.y, actual.y, 0.5f)
    }

    @Test fun `a menu widening the window keeps the pointer on the same spot of the screen`() {
        // 760×670 window at (900, 362); a menu opens right of and below it: the picture grows, its corner stays.
        val window = RemoteFrameArea(900.0, 362.0, 760.0, 670.0)
        val withMenu = RemoteFrameArea(900.0, 362.0, 1070.0, 930.0)
        assertNear(Offset(684f, 603f), reframedPoint(Offset(684f, 603f), 760, 670, window, 1070, 930, withMenu))
        // A menu opening up and to the left moves the picture's corner: the point moves with the screen spot.
        val leftMenu = RemoteFrameArea(700.0, 300.0, 960.0, 732.0)
        assertNear(Offset(884f, 665f), reframedPoint(Offset(684f, 603f), 760, 670, window, 960, 732, leftMenu))
        // Pictures scaled down for the phone: the same spot, at the new scale.
        assertNear(Offset(342f, 301.5f), reframedPoint(Offset(684f, 603f), 760, 670, window, 535, 465, withMenu))
    }

    @Test fun `without places on the screen the pointer keeps its fraction of the picture`() {
        assertNear(Offset(960f, 540f), reframedPoint(Offset(640f, 360f), 1280, 720, null, 1920, 1080, null))
        assertNear(Offset(960f, 540f), reframedPoint(Offset(640f, 360f), 1280, 720, RemoteFrameArea(0.0, 0.0, 1.0, 1.0), 1920, 1080, null))
    }

    @Test fun `the helper's place words are read only when all four make sense`() {
        assertEquals(SpaceFrameArea(593.32, 526.05, 760.0, 670.0), "geometry 760 670 593.32 526.05 760.00 670.00".split(' ').area(3))
        assertNull("geometry 760 670".split(' ').area(3))
        assertNull("geometry 760 670 1 2 0 670".split(' ').area(3))
        assertNull("geometry 760 670 1 2 x 670".split(' ').area(3))
        assertEquals(SpaceFrameArea(1.0, 2.0, 3.0, 4.0), "opened 760 670 nvh264enc 1 2 3 4".split(' ').area(4))
    }
}
