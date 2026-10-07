package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository

/** Host operations survive rotation; the UI must not offer a second install while one runs. */
data class RemoteScreenEnvironmentState(
    val connectionRevision: String? = null,
    val phase: RemoteScreenEnvironmentPhase = RemoteScreenEnvironmentPhase.IDLE,
    val probe: RemoteMachineProbe? = null,
    val error: Throwable? = null,
    val installation: RemoteCommandOutcome? = null,
    val installationError: Throwable? = null,
) {
    val busy: Boolean get() = phase != RemoteScreenEnvironmentPhase.IDLE
}

enum class RemoteScreenEnvironmentPhase { IDLE, PROBING, INSTALLING }

/** The command shown for confirmation is the one the repository runs. */
internal fun remoteCuaDriverCommand(upgradePath: String?): String = RemoteScreenRepository.cuaDriverCommand(upgradePath)
