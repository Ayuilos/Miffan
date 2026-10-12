package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressTest {
    @Test
    fun recognizesTailscaleAddressesAndNames() {
        listOf("100.64.0.8", "100.127.255.1", "win-desktop.tail1234.ts.net", "[fd7a:115c:a1e0::1]", "fd7a:115c:a1e0:ab12::5")
            .forEach { assertTrue(it, isTailnetAddress(it)) }
    }

    @Test
    fun leavesOtherAddressesAlone() {
        listOf("100.63.255.255", "100.128.0.1", "192.168.31.61", "10.0.2.2", "example.com", "win-desktop", "100.64.0", "fd00::1")
            .forEach { assertFalse(it, isTailnetAddress(it)) }
    }
}
