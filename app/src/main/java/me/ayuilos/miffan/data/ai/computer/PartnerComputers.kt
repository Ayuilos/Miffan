package me.ayuilos.miffan.data.ai.computer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.model.withWorkspaceBinding
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
    /** How the partner may operate it. */
    val computerUse: ComputerUseMode,
) {
    /** Easy mode shows the computer entry only when there is a screen to open. */
    val showsEntry: Boolean get() = screenEnabled
}

/** A computer Miffan knows, as easy chat's "my computers" shows it. */
data class KnownComputer(
    val hostId: String,
    /** The host's display name, e.g. "cachyos". */
    val name: String,
    /** "user@host", with ":port" only when the port is not 22. */
    val address: String,
    val platform: RemoteScreenPlatform,
    /** The host's screen is set up, so the screen page can open. */
    val screenEnabled: Boolean,
    /** The workspace easy chat uses for this computer; null until one exists. See [PartnerComputers.computerWorkspace]. */
    val workspaceId: String?,
    /** Remote workspaces on this host besides [workspaceId]; only the professional interface manages them. */
    val otherWorkspaceCount: Int,
    /** Partners bound to any workspace of this host, in the order of the partner list. */
    val partnerIds: List<Uuid>,
)

/** Deleting a computer that still has professional-interface workspaces is refused. */
class ComputerHasWorkspacesException(val count: Int) : IllegalStateException("The computer still has $count other workspaces")

/** Resolves which computer, if any, a partner is bound to. */
class PartnerComputers(
    private val settingsStore: SettingsStore,
    private val workspaces: WorkspaceRepository,
) {
    /** Every remote host, sorted by name. */
    fun observeAll(): Flow<List<KnownComputer>> = combine(
        settingsStore.settingsFlow,
        workspaces.listFlow(),
        workspaces.listHostsFlow(),
    ) { settings, workspaceList, hosts ->
        hosts.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { knownComputer(it, workspaceList, settings.assistants) }
    }.distinctUntilChanged()

    /** One remote host; null once it is deleted. */
    fun observeComputer(hostId: String): Flow<KnownComputer?> = observeAll()
        .map { computers -> computers.find { it.hostId == hostId } }
        .distinctUntilChanged()

    /**
     * The workspace easy chat uses for [hostId], creating one rooted at the account's home
     * directory when the host has none (this connects over SSH).
     */
    suspend fun ensureComputerWorkspace(hostId: String): String {
        computerWorkspace(hostId, workspaces.listFlow().first(), settingsStore.settingsFlow.value.assistants)
            ?.let { return it.id }
        val host = requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
        val home = workspaces.remoteHome(host.id)
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) host.name else "${host.name} $it" }
            .first { !workspaces.isNameTaken(it, null) }
        return workspaces.createRemoteWorkspace(name, host.id, home).id
    }

    /**
     * Gives the computer to [assistantIds]: binds each to [ensureComputerWorkspace] with computer use
     * set to ask first. Partners already bound to a workspace of this host keep their binding and mode.
     */
    suspend fun bind(hostId: String, assistantIds: Collection<Uuid>): Unit {
        if (assistantIds.isEmpty()) return
        val workspaceId = Uuid.parse(ensureComputerWorkspace(hostId))
        val hostWorkspaceIds = workspaces.listFlow().first()
            .filter { it.isRemote && it.remoteHostId == hostId }.map { it.id }.toSet()
        val selectedIds = assistantIds.toSet()
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.id !in selectedIds || assistant.workspaceId?.toString() in hostWorkspaceIds) assistant
                else assistant.withWorkspaceBinding(workspaceId).copy(computerUse = ComputerUseMode.ASK)
            })
        }
    }

    /**
     * Deletes the computer: its easy-chat workspace (unbinding the partners on it), then the host and
     * its saved credentials. Throws [ComputerHasWorkspacesException] without changing anything when the
     * host has other workspaces.
     */
    suspend fun delete(hostId: String): Unit {
        val workspaceList = workspaces.listFlow().first()
        val workspace = computerWorkspace(hostId, workspaceList, settingsStore.settingsFlow.value.assistants)
        val otherCount = workspaceList.count { it.isRemote && it.remoteHostId == hostId } - if (workspace != null) 1 else 0
        if (otherCount > 0) throw ComputerHasWorkspacesException(otherCount)
        workspace?.let { workspaces.delete(it.id) }
        workspaces.deleteHost(hostId)
    }

    private fun computerWorkspace(
        hostId: String,
        workspaceList: List<WorkspaceEntity>,
        assistants: List<Assistant>,
    ): WorkspaceEntity? {
        val partnerCounts = assistants.mapNotNull { it.workspaceId?.toString() }.groupingBy { it }.eachCount()
        return workspaceList.filter { it.isRemote && it.remoteHostId == hostId }.minWithOrNull(
            compareByDescending<WorkspaceEntity> { partnerCounts[it.id] ?: 0 }
                .thenBy { it.createdAt }.thenBy { it.id },
        )
    }

    private fun knownComputer(
        host: RemoteHostEntity,
        workspaceList: List<WorkspaceEntity>,
        assistants: List<Assistant>,
    ): KnownComputer {
        val hostWorkspaces = workspaceList.filter { it.isRemote && it.remoteHostId == host.id }
        val workspace = computerWorkspace(host.id, hostWorkspaces, assistants)
        val workspaceIds = hostWorkspaces.map { it.id }.toSet()
        return KnownComputer(
            hostId = host.id,
            name = host.name,
            address = "${host.username}@${host.host}" + if (host.port != 22) ":${host.port}" else "",
            platform = RemoteScreenPlatform.parse(host.screenPlatform),
            screenEnabled = host.screenEnabled,
            workspaceId = workspace?.id,
            otherWorkspaceCount = hostWorkspaces.size - if (workspace != null) 1 else 0,
            partnerIds = assistants.filter { it.workspaceId?.toString() in workspaceIds }.map { it.id },
        )
    }

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
            computerUse = assistant.computerUse,
        )
    }.distinctUntilChanged()

    /**
     * True when the partner works in a local workspace on this phone. Easy chat only shows it; the
     * professional interface is where it is set up.
     */
    fun observeUsesPhone(assistantId: Uuid): Flow<Boolean> = combine(
        settingsStore.settingsFlow,
        workspaces.listFlow(),
    ) { settings, workspaceList ->
        val bound = settings.getAssistantById(assistantId)?.workspaceId?.toString() ?: return@combine false
        workspaceList.find { it.id == bound }?.isRemote == false
    }.distinctUntilChanged()
}
