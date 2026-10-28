package me.ayuilos.miffan.data.ai.computer

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.model.withWorkspaceBinding
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.workspace.WorkspaceCommandResult

/** `miffan space probe`: whether the host can run Spaces. */
@Serializable
data class CuaSpaceProbe(
    val cua: Cua? = null,
    val daemon: Daemon? = null,
    /** Linux only; null on macOS, where cua brings its own Linux VM. */
    val docker: Docker? = null,
    @SerialName("disk_free_gb") val diskFreeGb: Int? = null,
    /** The host's size; null from helpers before 26. */
    val host: Host? = null,
    val spaces: List<Entry> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
) {
    @Serializable data class Cua(val path: String? = null, val version: String? = null)
    @Serializable data class Daemon(val running: Boolean = false)
    @Serializable data class Docker(val ok: Boolean = false, val detail: String? = null)
    @Serializable data class Entry(val id: String, val state: String? = null)
    @Serializable data class Host(
        val cpus: Int? = null,
        @SerialName("memory_mb") val memoryMb: Int? = null,
        /** macOS: cua's Linux VM that every Space shares. */
        val runtime: Runtime? = null,
    )
    @Serializable data class Runtime(val cpus: Int, @SerialName("memory_mb") val memoryMb: Int)

    val cuaInstalled: Boolean get() = cua?.path != null
}

/** `miffan space netcheck`: can the Space reach the internet. */
@Serializable
data class CuaSpaceNetcheck(
    val dns: Boolean = false,
    val https: Boolean = false,
    val detail: String? = null,
    /** "ufw" when the host firewall likely blocks the container bridge. */
    val hint: String? = null,
    val error: String? = null,
) {
    val online: Boolean get() = error == null && dns && https
}

@Serializable
private data class SpaceResult(val id: String? = null, val state: String? = null, val error: String? = null, val detail: String? = null)

/** A failed helper step, with the helper's error code (see remote-helper/PROTOCOL.md). */
class CuaSpaceException(val code: String, detail: String?) :
    IllegalStateException(detail?.takeIf { it.isNotBlank() }?.let { "$code: $it" } ?: code)

data class CreatedSpace(val workspaceId: String, val netcheck: CuaSpaceNetcheck?)

/**
 * A partner's own computer: a cua Space on one of the user's computers, bound to the partner as a
 * SPACE workspace. Every step runs the host helper's `space` command over SSH.
 */
class PartnerSpaces(
    private val settingsStore: SettingsStore,
    private val workspaces: WorkspaceRepository,
) {
    suspend fun probe(hostId: String): CuaSpaceProbe =
        parse<CuaSpaceProbe>(workspaces.runSpaceHelper(hostId, listOf("probe"), timeoutMillis = 60_000))
            .also { it.error?.let { code -> throw CuaSpaceException(code, it.detail) } }

    /** Installs the cua CLI (no account, no app) and starts its daemon. */
    suspend fun install(hostId: String): CuaSpaceProbe =
        parse<CuaSpaceProbe>(workspaces.runSpaceHelper(hostId, listOf("install"), timeoutMillis = INSTALL_TIMEOUT_MILLIS))
            .also { it.error?.let { code -> throw CuaSpaceException(code, it.detail) } }

    /**
     * Creates a Space on [hostId] and makes it [assistantId]'s computer, operated without asking first
     * (the Space can always be reset). The first Space on a host downloads its image, which takes minutes.
     */
    suspend fun create(hostId: String, assistantId: Uuid, tier: SpaceTier = SpaceTier.DEFAULT): CreatedSpace {
        val assistant = requireNotNull(settingsStore.settingsFlow.value.getAssistantById(assistantId)) { "Partner not found" }
        val host = requireNotNull(workspaces.getHostById(hostId)) { "Computer not found" }
        val name = "miffan-" + Uuid.random().toHexString().take(8)
        requireSuccess(parse<SpaceResult>(workspaces.runSpaceHelper(hostId, listOf("create", name) + tier.args(),
            timeoutMillis = CREATE_TIMEOUT_MILLIS)))
        val label = generateSequence(1) { it + 1 }
            .map { if (it == 1) "${assistant.name} · ${host.name}" else "${assistant.name} · ${host.name} $it" }
            .first { !workspaces.isNameTaken(it, null) }
        val taken = workspaces.listFlow().first().filter { it.isSpace && it.remoteHostId == hostId }.mapNotNull { it.spaceHandoff }.toSet()
        val workspace = workspaces.createSpaceWorkspace(label, hostId, name, tier.cpus, tier.memoryMb,
            handoffFolder(assistant.name, taken))
        val workspaceId = Uuid.parse(workspace.id)
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id != assistantId) it
                else it.withWorkspaceBinding(workspaceId).copy(computerUse = ComputerUseMode.AUTO)
            })
        }
        return CreatedSpace(workspace.id, runCatching { netcheck(workspace.id) }.getOrNull())
    }

    suspend fun netcheck(workspaceId: String): CuaSpaceNetcheck {
        val workspace = space(workspaceId)
        return parse(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("netcheck", requireNotNull(workspace.spaceName)), timeoutMillis = 60_000))
    }

    /** Deletes everything in the Space and starts it again from the image; the partner stays bound. */
    suspend fun reset(workspaceId: String) {
        val workspace = space(workspaceId)
        workspaces.forgetSpaceConnection(workspaceId)
        val tier = SpaceTier.of(workspace.spaceCpus, workspace.spaceMemoryMb) ?: SpaceTier.DEFAULT
        requireSuccess(parse<SpaceResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("reset", requireNotNull(workspace.spaceName)) + tier.args(), timeoutMillis = CREATE_TIMEOUT_MILLIS)))
        // A reset Space is a new machine: calls prepared for the old one must not land on it.
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.workspaceId?.toString() != workspaceId) it
                else it.copy(workspacePermissionRevision = Uuid.random().toString())
            })
        }
    }

    /** Changes the Space's size in place (Linux hosts); its files stay. */
    suspend fun resize(workspaceId: String, tier: SpaceTier) {
        val workspace = space(workspaceId)
        requireSuccess(parse<SpaceResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("resize", requireNotNull(workspace.spaceName)) + tier.args(), timeoutMillis = 60_000)))
        workspaces.updateSpaceSize(workspaceId, tier.cpus, tier.memoryMb)
    }

    /** Memory already promised to Spaces on [hostId], for sizing the next ones. */
    suspend fun usedMemoryMb(hostId: String): Int = workspaces.listFlow().first()
        .filter { it.isSpace && it.remoteHostId == hostId }
        .sumOf { it.spaceMemoryMb ?: SpaceTier.DEFAULT.memoryMb }

    /** Deletes the Space on the host, then its workspace (unbinding the partner). */
    suspend fun delete(workspaceId: String) {
        val workspace = space(workspaceId)
        val result = parse<SpaceResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("delete", requireNotNull(workspace.spaceName)), timeoutMillis = 120_000))
        if (result.error != null && result.error != "not_found") throw CuaSpaceException(result.error, result.detail)
        workspaces.delete(workspaceId)
    }

    private suspend fun space(workspaceId: String): WorkspaceEntity =
        requireNotNull(workspaces.getById(workspaceId)?.takeIf { it.isSpace }) { "Not a partner's own computer" }

    private fun SpaceTier.args() = listOf(cpus.toString(), memoryMb.toString())

    private fun requireSuccess(result: SpaceResult) {
        result.error?.let { throw CuaSpaceException(it, result.detail) }
    }

    private inline fun <reified T> parse(result: WorkspaceCommandResult): T {
        if (result.timedOut) throw CuaSpaceException("timeout", null)
        val line = result.stdout.trim().lineSequence().lastOrNull { it.isNotBlank() }
        return line?.let { runCatching { JsonInstant.decodeFromString<T>(it) }.getOrNull() }
            ?: throw CuaSpaceException("helper_failed", result.stderr.trim().take(300).ifEmpty { "exit ${result.exitCode}" })
    }

    companion object {
        /** The host folder all partners' handoff folders live in; English on purpose, whatever the app's language. */
        const val HANDOFF_ROOT = "Miffan Partners"

        /**
         * `Miffan Partners/<partner name>` relative to the host account's home, made safe for any file
         * system and kept apart from the other Spaces' folders on the host ([taken]).
         */
        internal fun handoffFolder(partnerName: String, taken: Set<String>): String {
            val safe = partnerName.map { if (it.code < 32 || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
                .trim().trim('.').take(60).trim().ifEmpty { "Partner" }
            return generateSequence(1) { it + 1 }
                .map { "$HANDOFF_ROOT/" + if (it == 1) safe else "$safe ($it)" }
                .first { it !in taken }
        }

        private const val INSTALL_TIMEOUT_MILLIS = 10 * 60_000L
        // First create on a host downloads the runtime and a 1.3 GB image.
        private const val CREATE_TIMEOUT_MILLIS = 30 * 60_000L
    }
}
