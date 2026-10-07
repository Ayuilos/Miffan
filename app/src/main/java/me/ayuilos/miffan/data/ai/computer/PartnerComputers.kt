package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

/** The computer a partner is bound to: a remote workspace and the host behind it. */
data class PartnerComputer(
    val assistantId: Uuid,
    val workspaceId: String,
    val hostId: String,
    /** The host's display name, e.g. "cachyos". */
    val name: String,
    val platform: RemoteScreenPlatform,
    /** The host's screen is set up, so the screen page can open. */
    val screenEnabled: Boolean,
    /** The partner may use the computer_* tools on it. */
    val computerUseEnabled: Boolean,
) {
    /** Easy mode shows the computer entry only when there is a screen to open. */
    val showsEntry: Boolean get() = screenEnabled
}

/** Resolves which computer, if any, a partner is bound to. */
class PartnerComputers(
    private val settingsStore: SettingsStore,
    private val workspaces: WorkspaceRepository,
) {
    /** Null when the partner is unbound or bound to a local workspace. */
    fun observe(assistantId: Uuid): Flow<PartnerComputer?> = combine(
        settingsStore.settingsFlow,
        workspaces.listFlow(),
        workspaces.listHostsFlow(),
    ) { settings, workspaceList, hosts ->
        val assistant = settings.getAssistantById(assistantId) ?: return@combine null
        val workspace = workspaceList.find { it.id == assistant.workspaceId?.toString() }
            ?.takeIf { it.isRemote } ?: return@combine null
        val host = hosts.find { it.id == workspace.remoteHostId } ?: return@combine null
        PartnerComputer(
            assistantId = assistantId,
            workspaceId = workspace.id,
            hostId = host.id,
            name = host.name,
            platform = RemoteScreenPlatform.parse(host.screenPlatform),
            screenEnabled = host.screenEnabled,
            computerUseEnabled = assistant.computerUseEnabled,
        )
    }.distinctUntilChanged()
}
