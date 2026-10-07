package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import me.ayuilos.miffan.data.repository.RemoteCommandOutcome
import me.ayuilos.miffan.data.repository.RemoteMachineProbe

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

/** Must match RemoteScreenRepository's command exactly, including quoting the upgrade path. */
internal fun remoteCuaDriverCommand(upgradePath: String?): String = if (upgradePath == null) {
    "/bin/bash -c \"\$(curl -fsSL https://cua.ai/driver/install.sh)\""
} else {
    "'" + upgradePath.replace("'", "'\\''") + "' update --apply"
}
