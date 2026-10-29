package me.ayuilos.miffan.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceKeyNameTest {
    @Test fun keysymsUseTheViewerNames() {
        // The names cua's own web viewer sends: DOM key names, lower-cased.
        assertEquals("enter", spaceKeyName(0xff0d))
        assertEquals("backspace", spaceKeyName(0xff08))
        assertEquals("arrowleft", spaceKeyName(0xff51))
        assertEquals("control", spaceKeyName(0xffe3))
        assertEquals("meta", spaceKeyName(0xffeb))
        assertEquals("f12", spaceKeyName(0xffc9))
        assertEquals("space", spaceKeyName(0x20))
        // Letters as the key's character, whatever the case; digits and symbols as themselves.
        assertEquals("c", spaceKeyName('C'.code))
        assertEquals("7", spaceKeyName('7'.code))
        assertEquals("/", spaceKeyName('/'.code))
        assertEquals("3", spaceKeyName(0xffb3))
        assertNull(spaceKeyName(0x1234))
    }
}
