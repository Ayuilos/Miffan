package me.ayuilos.miffan.data.ai.computer

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
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
    /** Windows: Docker Desktop installing in the background on the computer; null when none is under way or ran. */
    @SerialName("docker_install") val dockerInstall: DockerInstall? = null,
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
    /** [stage]: cua (installing cua first), download, install, done, restart (Windows must restart) or failed ([error] says why). */
    @Serializable data class DockerInstall(val stage: String, val bytes: Long = 0, val total: Long = 0, val error: String? = null) {
        val running: Boolean get() = stage == "cua" || stage == "download" || stage == "install"
    }
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
    /**
     * "ufw" when the host firewall blocks the Space (the fix is [me.ayuilos.miffan.data.ai.tools.spaceNetworkFixCommand]),
     * "firewall" for another firewall, "share_failed" when the host's network share could not be set up.
     */
    val hint: String? = null,
    /** "direct" (the Space's own network) or "share" (through the host, see remote-helper/PROTOCOL.md). */
    val via: String? = null,
    /** With [via] "share": the proxy the Space uses and the host's bridge interface it comes in on. */
    val proxy: String? = null,
    val bridge: String? = null,
    val error: String? = null,
) {
    val online: Boolean get() = error == null && dns && https
    val firewallFix: Boolean get() = hint == "ufw"
}

@Serializable
private data class SpaceResult(val id: String? = null, val state: String? = null, val error: String? = null, val detail: String? = null)

/** A failed helper step, with the helper's error code (see remote-helper/PROTOCOL.md). */
class CuaSpaceException(val code: String, detail: String?) :
    IllegalStateException(detail?.takeIf { it.isNotBlank() }?.let { "$code: $it" } ?: code)

data class CreatedSpace(val workspaceId: String, val netcheck: CuaSpaceNetcheck?)

@Serializable
private data class HandoffResult(val copied: List<String> = emptyList(), val error: String? = null, val detail: String? = null)

@Serializable
private data class HandoffRequest(val folder: String, val path: String? = null)

/** Files the owner put in the host's handoff folder that reached the Space's Shared folder at [at]. */
data class SpaceArrivals(val names: List<String>, val at: Long)

/** What the Space uses now: memory as the kernel counts it for its limit, CPU as a share of its cores. */
data class SpaceUsage(val memoryMb: Int, val memoryLimitMb: Int?, val cpuPercent: Int?)

/** Whether the Space's container runs, as the host lists it; [UNKNOWN] when the host does not list it (its daemon is off). */
enum class SpacePower { AWAKE, ASLEEP, UNKNOWN, UNREACHABLE }

internal fun spacePowerOf(probe: CuaSpaceProbe, spaceName: String): SpacePower {
    val entry = probe.spaces.firstOrNull { it.id == spaceName || it.id.endsWith(":$spaceName") } ?: return SpacePower.UNKNOWN
    return when (entry.state?.lowercase()) {
        "running" -> SpacePower.AWAKE
        "stopped", "paused", "exited" -> SpacePower.ASLEEP
        else -> SpacePower.UNKNOWN
    }
}

/**
 * A partner's own computer: a cua Space on one of the user's computers, bound to the partner as a
 * SPACE workspace. Every step runs the host helper's `space` command over SSH.
 */
class PartnerSpaces(
    private val settingsStore: SettingsStore,
    private val workspaces: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val fileLog: SpaceFileLog,
) {
    private val lastChecks = ConcurrentHashMap<String, CuaSpaceNetcheck>()
    private val lastPowers = ConcurrentHashMap<String, SpacePower>()
    private val lastUsages = ConcurrentHashMap<String, SpaceUsage>()
    private val syncs = ConcurrentHashMap<String, Sync>()
    private val arrivals = ConcurrentHashMap<String, SpaceArrivals>()

    private class Sync(val started: Long, val job: Deferred<Result<Unit>>)
    /** Spaces whose desktop extras (font, video codecs) are in place, in this run of the app. */
    private val desktopsReady = ConcurrentHashMap.newKeySet<String>()

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
        val workspace = workspaces.createSpaceWorkspace(label, hostId, name, tier.cpus, tier.memoryMb,
            handoffFolder(assistant.name, takenHandoffs(hostId)))
        val workspaceId = Uuid.parse(workspace.id)
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map {
                if (it.id != assistantId) it
                else it.withWorkspaceBinding(workspaceId).copy(computerUse = ComputerUseMode.AUTO)
            })
        }
        return CreatedSpace(workspace.id, runCatching { netcheck(workspace.id) }.getOrNull())
    }

    /** Checks the Space's network; offline on its own, the helper moves it onto the host's network share. */
    suspend fun netcheck(workspaceId: String): CuaSpaceNetcheck {
        val workspace = space(workspaceId)
        val check = parse<CuaSpaceNetcheck>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("netcheck", requireNotNull(workspace.spaceName)), timeoutMillis = 90_000))
        lastChecks[workspaceId] = check
        // Processes started from now on get the share's proxy.
        if (check.via == "share") workspaces.useSpaceProxy(workspaceId, check.proxy)
        if (check.online) ensureDesktop(workspaceId)
        return check
    }

    /**
     * Installs, in the background, what the Space's image lacks for everyday use: a light font for the app's language
     * (it only covers Latin and Cyrillic, so Chinese, Japanese or Korean show as boxes) and the browser's video codecs
     * (without them sites like Bilibili refuse to play). Quick when both are there; a failure (apt busy, offline) is
     * tried again next time. Runs after a successful network check and when the Space's screen opens.
     */
    fun ensureDesktop(workspaceId: String) {
        if (!desktopsReady.add(workspaceId)) return
        scope.launch {
            val installed = runCatching {
                workspaces.runInSpace(workspaceId, spaceDesktopCommand(spaceFontPackage(Locale.getDefault())), DESKTOP_TIMEOUT_MILLIS).exitCode == 0
            }.getOrDefault(false)
            if (!installed) desktopsReady.remove(workspaceId)
        }
    }

    /** The last network check of this Space in this run of the app. */
    fun lastNetcheck(workspaceId: String): CuaSpaceNetcheck? = lastChecks[workspaceId]

    /** Deletes everything in the Space and starts it again from the image; the partner stays bound. */
    suspend fun reset(workspaceId: String) {
        val workspace = space(workspaceId)
        workspaces.forgetSpaceConnection(workspaceId)
        val tier = SpaceTier.of(workspace.spaceCpus, workspace.spaceMemoryMb) ?: SpaceTier.DEFAULT
        requireSuccess(parse<SpaceResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("reset", requireNotNull(workspace.spaceName)) + tier.args(), timeoutMillis = CREATE_TIMEOUT_MILLIS)))
        fileLog.clear(workspaceId)
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

    /**
     * The Space's handoff folder on its host, relative to the host account's home. A Space made before
     * handoff folders existed gets one now, named after [partnerName].
     */
    suspend fun handoffFolder(workspaceId: String, partnerName: String): String? {
        val workspace = workspaces.getById(workspaceId)?.takeIf { it.isSpace } ?: return null
        workspace.spaceHandoff?.let { return it }
        val hostId = workspace.remoteHostId ?: return null
        return workspaces.setSpaceHandoffIfMissing(workspaceId, handoffFolder(partnerName, takenHandoffs(hostId)))
    }

    private suspend fun takenHandoffs(hostId: String): Set<String> = workspaces.listFlow().first()
        .filter { it.isSpace && it.remoteHostId == hostId }.mapNotNull { it.spaceHandoff }.toSet()

    /**
     * Copies [path] (a file or folder in the Space's Shared folder) into the Space's handoff folder on its
     * host; returns where each file landed, relative to the host account's home. Names already there get
     * a " (2)" suffix instead of being overwritten.
     */
    suspend fun handOut(workspaceId: String, path: String): List<String> {
        val workspace = space(workspaceId)
        val folder = requireNotNull(workspace.spaceHandoff) { "No handoff folder" }
        val result = parse<HandoffResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("handoff", "out", requireNotNull(workspace.spaceName)),
            stdin = JsonInstant.encodeToString(HandoffRequest(folder, path)).encodeToByteArray(), timeoutMillis = HANDOFF_TIMEOUT_MILLIS))
        result.error?.let { throw CuaSpaceException(it, result.detail) }
        fileLog.record(workspaceId, listOf(path.trim().trimEnd('/')), SpaceFileDirection.FROM_PARTNER)
        return result.copied.map { "$folder/$it" }
    }

    /** Which way files went between the owner and this Space, newest first. */
    fun fileNotes(workspaceId: String): Flow<List<SpaceFileNote>> = fileLog.notes(workspaceId)

    /** [path] (relative to the Shared folder) was deleted. */
    fun forgetFile(workspaceId: String, path: String) = fileLog.forget(workspaceId, path)

    /**
     * Brings what the owner newly put in the host's handoff folder into the Space's Shared folder, at most
     * every [SYNC_INTERVAL_MILLIS]; waits up to [waitMillis] for it and lets a slower copy finish on its own.
     * Returns the files that arrived in the last [ARRIVALS_SHOWN_MILLIS], for the partner to hear about.
     */
    suspend fun syncIn(workspaceId: String, waitMillis: Long = SYNC_WAIT_MILLIS): SpaceArrivals? {
        val now = System.currentTimeMillis()
        val sync = syncs.compute(workspaceId) { _, last ->
            if (last != null && (!last.job.isCompleted || now - last.started < SYNC_INTERVAL_MILLIS)) last
            else Sync(now, scope.async { runCatching { copyIn(workspaceId) } })
        }!!
        withTimeoutOrNull(waitMillis) { sync.job.await() }
        return arrivals[workspaceId]?.takeIf { System.currentTimeMillis() - it.at < ARRIVALS_SHOWN_MILLIS }
    }

    /** The owner put [names] in the Shared folder from the phone; the partner hears about them like host drops. */
    fun noteArrivals(workspaceId: String, names: List<String>) {
        if (names.isEmpty()) return
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { fileLog.record(workspaceId, names, SpaceFileDirection.TO_PARTNER, now) }
        arrivals.merge(workspaceId, SpaceArrivals(names, now)) { old, new ->
            if (now - old.at < ARRIVALS_SHOWN_MILLIS) SpaceArrivals((old.names + new.names).distinct(), now) else new
        }
    }

    private suspend fun copyIn(workspaceId: String) {
        val workspace = space(workspaceId)
        val folder = workspace.spaceHandoff ?: return
        val result = parse<HandoffResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("handoff", "in", requireNotNull(workspace.spaceName)),
            stdin = JsonInstant.encodeToString(HandoffRequest(folder)).encodeToByteArray(), timeoutMillis = HANDOFF_TIMEOUT_MILLIS))
        result.error?.let { throw CuaSpaceException(it, result.detail) }
        noteArrivals(workspaceId, result.copied)
    }

    /**
     * What the Space uses now, read from its cgroup; null when it cannot be read. Any command in the Space
     * starts a stopped one first, so ask only when [power] says it is awake.
     */
    suspend fun usage(workspaceId: String): SpaceUsage? {
        val workspace = space(workspaceId)
        val result = workspaces.runInSpace(workspaceId, USAGE_COMMAND, 15_000)
        if (result.exitCode != 0) return null
        return parseSpaceUsage(result.stdout, workspace.spaceCpus)?.also { lastUsages[workspaceId] = it }
    }

    /** The last usage read in this run of the app. */
    fun lastUsage(workspaceId: String): SpaceUsage? = lastUsages[workspaceId]

    /**
     * Whether the Space is awake, from the host's read-only listing: unlike a command in the Space it does not
     * wake a sleeping one. [SpacePower.UNREACHABLE] when the host does not answer.
     */
    suspend fun power(workspaceId: String): SpacePower {
        val workspace = space(workspaceId)
        val result = try {
            spacePowerOf(probe(requireNotNull(workspace.remoteHostId)), requireNotNull(workspace.spaceName))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SpacePower.UNREACHABLE
        }
        lastPowers[workspaceId] = result
        return result
    }

    /** The last answer of [power] in this run of the app. */
    fun lastPower(workspaceId: String): SpacePower? = lastPowers[workspaceId]

    /** Deletes the Space on the host, then its workspace (unbinding the partner). */
    suspend fun delete(workspaceId: String) {
        val workspace = space(workspaceId)
        val result = parse<SpaceResult>(workspaces.runSpaceHelper(requireNotNull(workspace.remoteHostId),
            listOf("delete", requireNotNull(workspace.spaceName)), timeoutMillis = 120_000))
        if (result.error != null && result.error != "not_found") throw CuaSpaceException(result.error, result.detail)
        workspaces.delete(workspaceId)
        fileLog.clear(workspaceId)
        lastPowers.remove(workspaceId)
        lastUsages.remove(workspaceId)
        lastChecks.remove(workspaceId)
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

        /** Where the owner's and the partner's files meet inside the Space, on its desktop (helper's SPACE_HANDOFF_DIR). */
        const val SHARED_DIR = "/home/cua/Desktop/Shared"
        /** [SHARED_DIR] relative to a Space workspace's root, for the workspace file APIs. */
        const val SHARED_RELATIVE = "Desktop/Shared"
        /** What the phone may upload into a Space at once; uploads stream, so this guards the Space's disk. */
        const val PHONE_UPLOAD_MAX_BYTES = 2L * 1024 * 1024 * 1024

        // A Windows install downloads Docker Desktop (about 600 MB) too.
        private const val INSTALL_TIMEOUT_MILLIS = 30 * 60_000L
        private const val HANDOFF_TIMEOUT_MILLIS = 5 * 60_000L
        private const val SYNC_WAIT_MILLIS = 4_000L
        private const val SYNC_INTERVAL_MILLIS = 20_000L
        private const val ARRIVALS_SHOWN_MILLIS = 10 * 60_000L

        /**
         * Memory as docker counts it (current minus inactive file cache), the limit, and CPU time over half
         * a second. cgroup v2, which both a Linux host's Spaces and cua's VM on a Mac use.
         */
        private const val USAGE_COMMAND = "c=/sys/fs/cgroup; u1=\$(awk '/^usage_usec/{print \$2}' \$c/cpu.stat); sleep 0.5; " +
            "u2=\$(awk '/^usage_usec/{print \$2}' \$c/cpu.stat); " +
            "echo \"\$(cat \$c/memory.current) \$(cat \$c/memory.max) \$(awk '/^inactive_file /{print \$2}' \$c/memory.stat) \$u1 \$u2\""
        // First create on a host downloads the runtime and a 1.3 GB image.
        private const val CREATE_TIMEOUT_MILLIS = 30 * 60_000L
    }
}

/** Parses the usage command's "current max inactive_file usec1 usec2" line ([cpus]: the Space's cores). */
internal fun parseSpaceUsage(output: String, cpus: Int?): SpaceUsage? {
    val fields = output.trim().lineSequence().lastOrNull()?.trim()?.split(Regex("\\s+")) ?: return null
    if (fields.size < 5) return null
    val current = fields[0].toLongOrNull() ?: return null
    val inactive = fields[2].toLongOrNull() ?: 0
    val limit = fields[1].toLongOrNull()
    val cpu = fields[3].toLongOrNull()?.let { a -> fields[4].toLongOrNull()?.let { b -> b - a } }
    val mb = 1024L * 1024
    return SpaceUsage(
        memoryMb = ((current - inactive).coerceAtLeast(0) / mb).toInt(),
        memoryLimitMb = limit?.let { (it / mb).toInt() },
        // usec over 0.5 s on one core is 500 000.
        cpuPercent = cpu?.let { (it * 100 / (500_000L * (cpus ?: 1).coerceAtLeast(1))).toInt().coerceIn(0, 100) },
    )
}

/** The light font package that shows [locale]'s script in a Space; null when its image already does. */
internal fun spaceFontPackage(locale: Locale): String? = when (locale.language) {
    "zh" -> "fonts-wqy-microhei" // Simplified and Traditional
    "ja" -> "fonts-ipafont-gothic"
    "ko" -> "fonts-nanum"
    else -> null
}

/**
 * Installs [font] (if any) and the browser's video codecs unless they are there. Both live in Ubuntu's own archive, so
 * the image's package lists usually do: it installs straight away and refreshes the lists only when that fails, and
 * then only Ubuntu's sources, not the image's third-party ones (Mozilla, Docker, a PPA), which are slow or unreachable.
 * Downloads go through the Space's proxy like everything else. Waits for an apt the partner may be running instead of
 * failing. The exit status is the font's; codecs are best effort.
 *
 * The window manager and panel load their fonts once, at start, so a font that arrived after them leaves window titles
 * and taskbar buttons as boxes while pages look right; they are restarted when that is the case.
 */
internal fun spaceDesktopCommand(font: String?): String = buildString {
    append("U=/etc/apt/sources.list.d/ubuntu.sources; O='-o Acquire::http::Timeout=10 -o Acquire::https::Timeout=10 -o Acquire::Retries=0'; ")
    append("D=/tmp/miffan-apt-\$(id -u); if [ -e \$U ]; then mkdir -p \$D && ln -sf \$U \$D/ && ")
    append("O=\"\$O -o Dir::Etc::sourcelist=/dev/null -o Dir::Etc::sourceparts=\$D -o APT::Get::List-Cleanup=0\"; fi; ")
    append("inst() { sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=120 install -y -qq --no-install-recommends \"\$@\"; }; ")
    append("lists() { [ -n \"\$fresh\" ] || sudo -n apt-get \$O -o DPkg::Lock::Timeout=120 update -qq >/dev/null 2>&1; fresh=1; }; ")
    if (font != null) append("dpkg -s $font >/dev/null 2>&1 || inst $font >/dev/null 2>&1 || { lists; inst $font; } || exit 1; ")
    append("dpkg -s $SPACE_CODECS >/dev/null 2>&1 || inst $SPACE_CODECS >/dev/null 2>&1 || { lists; inst $SPACE_CODECS >/dev/null 2>&1; }; ")
    if (font != null) {
        append("wm=\$(pgrep -x xfwm4 | head -1); ")
        append("if [ -n \"\$wm\" ] && [ \$(stat -c %Y /var/lib/dpkg/info/$font.list) -gt \$((\$(date +%s) - \$(ps -o etimes= -p \$wm))) ]; then ")
        append("export DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/cua-desktop/bus; ")
        append("setsid -f xfwm4 --replace </dev/null >/dev/null 2>&1; sleep 2; timeout 10 xfce4-panel -r </dev/null >/dev/null 2>&1; fi; ")
    }
    append("true")
}

/** H.264 and friends for Firefox, which only plays what the system's libavcodec decodes. */
private const val SPACE_CODECS = "libavcodec-extra"

private const val DESKTOP_TIMEOUT_MILLIS = 600_000L
