package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.data.ai.computer.ComputerPermissions
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
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
        assertFalse(ComputerSetupState(probe = probe(session = false)).prepared)
        assertFalse(ComputerSetupState(probe = probe(server = "none")).prepared)
        assertFalse(ComputerSetupState(probe = probe(cuaOk = false)).prepared)
        assertFalse(ComputerSetupState().prepared)
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
