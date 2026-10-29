package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.data.ai.computer.CuaSpaceProbe
import me.ayuilos.miffan.data.ai.computer.SpaceSetupJob

/** The package manager family of a Linux host, as far as the Docker install command is concerned. */
enum class LinuxFamily { ARCH, DEBIAN, FEDORA, SUSE }

/**
 * The family named by `ID` and `ID_LIKE` of /etc/os-release (`ID=cachyos`, `ID_LIKE=arch`, quoted or not), or
 * null for anything else. Red Hat's rebuilds say they are like Fedora but do not ship Docker's package, so they stay unknown.
 */
internal fun linuxFamily(osRelease: String): LinuxFamily? {
    val ids = osRelease.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
        .flatMap { it.substringAfter('=').trim('"', '\'').lowercase().split(' ', '\t').asSequence() }
        .filter { it.isNotEmpty() }
        .toSet()
    return when {
        ids.any { it in RED_HAT } -> null
        ids.any { it == "arch" || it in ARCH_BASED } -> LinuxFamily.ARCH
        ids.any { it == "debian" || it == "ubuntu" } -> LinuxFamily.DEBIAN
        "fedora" in ids -> LinuxFamily.FEDORA
        ids.any { it == "suse" || it.startsWith("opensuse") } -> LinuxFamily.SUSE
        else -> null
    }
}

private val RED_HAT = setOf("rhel", "centos", "rocky", "almalinux", "ol")
private val ARCH_BASED = setOf("cachyos", "manjaro", "endeavouros", "artix", "garuda")

/** What the helper's Docker check found wrong. */
internal enum class DockerProblem { NOT_INSTALLED, NOT_USABLE }

/** The helper words a missing binary as "docker not installed"; any other failure is Docker not answering this account. */
internal fun dockerProblem(docker: CuaSpaceProbe.Docker?): DockerProblem? = when {
    docker == null || docker.ok -> null
    docker.detail?.contains("not installed", ignoreCase = true) == true -> DockerProblem.NOT_INSTALLED
    else -> DockerProblem.NOT_USABLE
}

private const val DOCKER_START = "sudo systemctl enable --now docker && sudo usermod -aG docker \$USER"

/**
 * The one command that fixes [problem] on a host of [family], or null when there is no safe guess (an unknown
 * system that has no Docker). A Docker that is there but not usable needs the same two steps on every system.
 */
internal fun dockerFixCommand(problem: DockerProblem, family: LinuxFamily?): String? = when (problem) {
    DockerProblem.NOT_USABLE -> DOCKER_START
    DockerProblem.NOT_INSTALLED -> when (family) {
        LinuxFamily.ARCH -> "sudo pacman -S --needed docker"
        LinuxFamily.DEBIAN -> "sudo apt-get install -y docker.io"
        LinuxFamily.FEDORA -> "sudo dnf install -y moby-engine"
        LinuxFamily.SUSE -> "sudo zypper install -y docker"
        null -> null
    }?.let { "$it && $DOCKER_START" }
}

/** How far one line of the run under way is. */
internal enum class PrepareRow { DONE, LIVE, LATER, FAILED }

/** The four lines: check the computer, install cua, download and start, try the internet. The first two happen before a run exists. */
internal fun prepareRows(stage: SpaceSetupJob.Stage?): List<PrepareRow> = when (stage) {
    null, SpaceSetupJob.Stage.CREATING -> listOf(PrepareRow.DONE, PrepareRow.DONE, PrepareRow.LIVE, PrepareRow.LATER)
    SpaceSetupJob.Stage.CHECKING_NETWORK -> listOf(PrepareRow.DONE, PrepareRow.DONE, PrepareRow.DONE, PrepareRow.LIVE)
    SpaceSetupJob.Stage.DONE -> List(4) { PrepareRow.DONE }
    SpaceSetupJob.Stage.FAILED -> listOf(PrepareRow.DONE, PrepareRow.DONE, PrepareRow.FAILED, PrepareRow.LATER)
}

/** Things to ask a partner that has a fresh mini box; without the internet only what works offline. */
internal enum class SpaceIdea { WEATHER, PAPERS, PYTHON, SORT, TOUR }

internal fun spaceIdeas(online: Boolean): List<SpaceIdea> =
    if (online) listOf(SpaceIdea.WEATHER, SpaceIdea.PAPERS, SpaceIdea.PYTHON) else listOf(SpaceIdea.SORT, SpaceIdea.TOUR)
