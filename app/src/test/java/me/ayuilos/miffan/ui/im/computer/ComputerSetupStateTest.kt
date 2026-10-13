package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.data.ai.computer.ComputerPermissions
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerSetupStateTest {
    private fun probe(
        os: String = "linux",
        session: Boolean = true,
        server: String? = "wayvnc",
        running: Boolean = false,
        cuaOk: Boolean = true,
    ) = RemoteMachineProbe(
        helper = 5, os = os, arch = "x86_64",
        session = RemoteMachineProbe.Session(session, if (os == "macos") "quartz" else "wayland"),
        cua = RemoteMachineProbe.Cua(path = "/usr/local/bin/cua-driver", version = "0.34.0", min = "0.34.0", ok = cuaOk),
        vnc = RemoteMachineProbe.Vnc(server = server, running = running),
    )

    @Test
    fun linuxIsPreparedWithoutARunningServerBecauseTheHelperStartsIt() {
        assertTrue(ComputerSetupState(probe = probe()).prepared)
    }

    @Test
    fun eachMissingPieceBlocksPreparation() {
        assertFalse(ComputerSetupState(probe = probe(session = false), wantsScreen = true).prepared)
        assertFalse(ComputerSetupState(probe = probe(server = "none")).prepared)
        assertFalse(ComputerSetupState(probe = probe(cuaOk = false)).prepared)
        assertFalse(ComputerSetupState().prepared)
    }

    @Test
    fun screenDefaultsOffOnlyWhenNoDesktopWasFound() {
        assertTrue(ComputerSetupState().usesScreen)
        assertTrue(ComputerSetupState(probe = probe()).usesScreen)
        assertFalse(ComputerSetupState(probe = probe(session = false)).usesScreen)
        assertTrue(ComputerSetupState(probe = probe(session = false), wantsScreen = true).usesScreen)
        assertFalse(ComputerSetupState(probe = probe(), wantsScreen = false).usesScreen)
    }

    @Test
    fun withoutTheScreenNothingNeedsChecking() {
        assertTrue(ComputerSetupState(probe = probe(session = false, server = "none", cuaOk = false)).prepared)
        // A failed check still lets a server or NAS continue once the screen is off.
        assertTrue(ComputerSetupState(wantsScreen = false).prepared)
    }

    @Test
    fun progressSkipsTheScreenStepsWithoutAScreen() {
        assertEquals(4 to 6, computerSetupProgress(ComputerSetupState(step = ComputerSetupStep.PREPARE, wantsScreen = false)))
        assertEquals(5 to 6, computerSetupProgress(ComputerSetupState(step = ComputerSetupStep.BIND, wantsScreen = false)))
        assertEquals(6 to 7, computerSetupProgress(ComputerSetupState(step = ComputerSetupStep.BIND, probe = probe())))
        val mac = probe(os = "macos", server = "macos-screen-sharing", running = true)
        assertEquals(7 to 8, computerSetupProgress(ComputerSetupState(step = ComputerSetupStep.BIND, probe = mac)))
        assertEquals(5 to 6, computerSetupProgress(ComputerSetupState(step = ComputerSetupStep.BIND, probe = mac, wantsScreen = false)))
    }

    @Test
    fun macScreenSharingMustBeSwitchedOn() {
        assertFalse(ComputerSetupState(probe = probe(os = "macos", server = "macos-screen-sharing")).prepared)
        assertTrue(ComputerSetupState(probe = probe(os = "macos", server = "macos-screen-sharing", running = true)).prepared)
    }

    @Test
    fun macNeedsBothGrantsBeforeTheTestPasses() {
        val mac = probe(os = "macos", server = "macos-screen-sharing", running = true)
        assertFalse(ComputerSetupState(probe = mac, partnerChecked = true).tested)
        assertFalse(ComputerSetupState(probe = mac, partnerChecked = true, permissions = ComputerPermissions(true, false)).tested)
        assertTrue(ComputerSetupState(probe = mac, partnerChecked = true, permissions = ComputerPermissions(true, true)).tested)
        assertTrue(ComputerSetupState(probe = probe(), partnerChecked = true).tested)
    }
}
