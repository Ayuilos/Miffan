package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.data.ai.computer.CuaSpaceProbe
import me.ayuilos.miffan.data.ai.computer.SpaceSetupJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceSetupLogicTest {
    @Test
    fun archDerivativesAreRecognisedByIdOrIdLike() {
        assertEquals(LinuxFamily.ARCH, linuxFamily("ID=cachyos\nID_LIKE=arch\n"))
        assertEquals(LinuxFamily.ARCH, linuxFamily("ID=arch"))
        assertEquals(LinuxFamily.ARCH, linuxFamily("ID=endeavouros\nID_LIKE=\"arch\""))
    }

    @Test
    fun debianFamilyIncludesUbuntuDerivatives() {
        assertEquals(LinuxFamily.DEBIAN, linuxFamily("ID=ubuntu\nID_LIKE=debian"))
        assertEquals(LinuxFamily.DEBIAN, linuxFamily("ID=linuxmint\nID_LIKE=\"ubuntu debian\""))
        assertEquals(LinuxFamily.DEBIAN, linuxFamily("ID=debian"))
    }

    @Test
    fun fedoraAndSuse() {
        assertEquals(LinuxFamily.FEDORA, linuxFamily("ID=fedora"))
        assertEquals(LinuxFamily.SUSE, linuxFamily("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\""))
        assertEquals(LinuxFamily.SUSE, linuxFamily("ID=sles\nID_LIKE=\"suse\""))
    }

    @Test
    fun redHatRebuildsAndUnknownSystemsGetNoGuess() {
        assertNull(linuxFamily("ID=rocky\nID_LIKE=\"rhel centos fedora\""))
        assertNull(linuxFamily("ID=almalinux\nID_LIKE=\"rhel centos fedora\""))
        assertNull(linuxFamily("ID=nixos"))
        assertNull(linuxFamily(""))
        assertNull(linuxFamily("NAME=\"Arch Linux\"\nPRETTY_NAME=\"Arch\""))
    }

    @Test
    fun dockerProblemFollowsWhatTheHelperReports() {
        assertNull(dockerProblem(null))
        assertNull(dockerProblem(CuaSpaceProbe.Docker(ok = true)))
        assertEquals(DockerProblem.NOT_INSTALLED, dockerProblem(CuaSpaceProbe.Docker(ok = false, detail = "docker not installed")))
        assertEquals(DockerProblem.NOT_USABLE, dockerProblem(CuaSpaceProbe.Docker(ok = false, detail = "permission denied while trying to connect to the Docker daemon socket")))
        assertEquals(DockerProblem.NOT_USABLE, dockerProblem(CuaSpaceProbe.Docker(ok = false)))
    }

    @Test
    fun installCommandFollowsTheFamilyAndNeverGuesses() {
        val arch = dockerFixCommand(DockerProblem.NOT_INSTALLED, LinuxFamily.ARCH)
        assertEquals("sudo pacman -S --needed docker && sudo systemctl enable --now docker && sudo usermod -aG docker \$USER", arch)
        assertTrue(dockerFixCommand(DockerProblem.NOT_INSTALLED, LinuxFamily.DEBIAN)!!.startsWith("sudo apt-get install -y docker.io && "))
        assertTrue(dockerFixCommand(DockerProblem.NOT_INSTALLED, LinuxFamily.FEDORA)!!.startsWith("sudo dnf install -y moby-engine && "))
        assertTrue(dockerFixCommand(DockerProblem.NOT_INSTALLED, LinuxFamily.SUSE)!!.startsWith("sudo zypper install -y docker && "))
        assertNull(dockerFixCommand(DockerProblem.NOT_INSTALLED, null))
    }

    @Test
    fun anInstalledDockerNeedsTheSameTwoStepsOnEverySystem() {
        val expected = "sudo systemctl enable --now docker && sudo usermod -aG docker \$USER"
        assertEquals(expected, dockerFixCommand(DockerProblem.NOT_USABLE, null))
        assertEquals(expected, dockerFixCommand(DockerProblem.NOT_USABLE, LinuxFamily.ARCH))
        assertFalse(dockerFixCommand(DockerProblem.NOT_USABLE, LinuxFamily.DEBIAN)!!.contains("install"))
    }

    @Test
    fun prepareRowsAdvanceWithTheRun() {
        val done = PrepareRow.DONE
        assertEquals(listOf(done, done, PrepareRow.LIVE, PrepareRow.LATER), prepareRows(SpaceSetupJob.Stage.CREATING))
        assertEquals(prepareRows(SpaceSetupJob.Stage.CREATING), prepareRows(null))
        assertEquals(listOf(done, done, done, PrepareRow.LIVE), prepareRows(SpaceSetupJob.Stage.CHECKING_NETWORK))
        assertEquals(List(4) { done }, prepareRows(SpaceSetupJob.Stage.DONE))
        assertEquals(listOf(done, done, PrepareRow.FAILED, PrepareRow.LATER), prepareRows(SpaceSetupJob.Stage.FAILED))
    }

    @Test
    fun offlineBoxesOnlyGetIdeasThatWorkOffline() {
        assertEquals(listOf(SpaceIdea.WEATHER, SpaceIdea.PAPERS, SpaceIdea.PYTHON), spaceIdeas(online = true))
        val offline = spaceIdeas(online = false)
        assertTrue(offline.isNotEmpty())
        assertTrue(offline.none { it == SpaceIdea.WEATHER || it == SpaceIdea.PAPERS || it == SpaceIdea.PYTHON })
    }
}
