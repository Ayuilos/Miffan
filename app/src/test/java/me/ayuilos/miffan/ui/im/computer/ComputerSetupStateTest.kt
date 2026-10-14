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
    fun linuxIsReadyWithoutARunningServerBecauseTheHelperStartsIt() {
        assertTrue(ComputerSetupState(probe = probe()).ready)
    }

    @Test
    fun eachMissingPieceBlocksReadiness() {
        assertFalse(ComputerSetupState(probe = probe(session = false), wantsScreen = true).ready)
        assertFalse(ComputerSetupState(probe = probe(server = "none")).ready)
        assertFalse(ComputerSetupState(probe = probe(cuaOk = false)).ready)
        assertFalse(ComputerSetupState().ready)
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
    fun withoutTheScreenOnlyTheConnectionCounts() {
        assertTrue(ComputerSetupState(probe = probe(session = false, server = "none", cuaOk = false)).ready)
        // A server or NAS continues once connected, whatever its screen checks say.
        assertTrue(ComputerSetupState(probe = probe(cuaOk = false), wantsScreen = false).ready)
        assertFalse(ComputerSetupState(wantsScreen = false).ready)
    }

    @Test
    fun macScreenSharingMustBeSwitchedOn() {
        val off = probe(os = "macos", server = "macos-screen-sharing")
        val on = probe(os = "macos", server = "macos-screen-sharing", running = true)
        assertFalse(ComputerSetupState(probe = off).machineReady)
        assertTrue(ComputerSetupState(probe = on).machineReady)
    }

    @Test
    fun macNeedsItsAccountAndBothGrants() {
        val mac = probe(os = "macos", server = "macos-screen-sharing", running = true)
        assertFalse(ComputerSetupState(probe = mac).ready)
        assertFalse(ComputerSetupState(probe = mac, macAccount = "ekk").ready)
        assertFalse(ComputerSetupState(probe = mac, macAccount = "ekk", permissions = ComputerPermissions(true, false)).ready)
        assertTrue(ComputerSetupState(probe = mac, macAccount = "ekk", permissions = ComputerPermissions(true, true)).ready)
        assertFalse(ComputerSetupState(probe = mac, permissions = ComputerPermissions(true, true)).ready)
    }

    @Test
    fun systemComesFromTheFoundComputerUntilTheProbeSaysOtherwise() {
        val target = SetupTarget("box", "10.0.0.2", 22, me.ayuilos.miffan.data.remote.NearbySystem.WINDOWS)
        assertTrue(ComputerSetupState(target = target).isWindows)
        assertFalse(ComputerSetupState(target = target, probe = probe()).isWindows)
    }
}
