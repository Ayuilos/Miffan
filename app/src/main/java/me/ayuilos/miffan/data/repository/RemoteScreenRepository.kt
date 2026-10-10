package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenProtocol
import me.rerere.rdp.RdpSession
import me.rerere.rdp.RdpOptions
import me.rerere.rdp.RdpCredentials
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.screen.RemoteScreenFrameSink
import me.rerere.workspace.screen.RemoteScreenOptions
import me.rerere.workspace.screen.RemoteScreenSession
import me.rerere.workspace.screen.RfbCredentials
import me.rerere.workspace.screen.RfbJpegDecoder

/** Screen settings of one host, without the stored password itself. */
data class RemoteHostScreenConfig(
    val enabled: Boolean,
    val endpoint: RemoteScreenEndpoint,
    val auth: RemoteScreenAuth,
    /** Blank means the host's SSH username. */
    val username: String,
    val platform: RemoteScreenPlatform,
    val hasPassword: Boolean,
    val protocol: RemoteScreenProtocol = RemoteScreenProtocol.AUTO,
    val rdpUsername: String = "",
    val rdpCertificateSha256: String? = null,
    val streamEnabled: Boolean = false,
    val streamCertificateSha256: String? = null,
)

enum class RemoteScreenPlatform { UNKNOWN, MACOS, LINUX;
    val storageName: String get() = if (this == UNKNOWN) "" else name.lowercase()
    companion object {
        fun parse(value: String): RemoteScreenPlatform = entries.firstOrNull { it.storageName == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Why a screen could not be opened; the page maps each reason to a fix the user can make. */
enum class RemoteScreenProblem {
    NOT_FOUND, NOT_ENABLED, BAD_ENDPOINT, PASSWORD_MISSING,
    /** Host-level actions need a remote workspace on the host to borrow its SSH connection. */
    NO_WORKSPACE,
    /** The helper found no logged-in graphical session for this account. */
    NO_GRAPHICAL_SESSION,
    /** No supported VNC server is installed for the session type (see [RemoteScreenUnavailableException.detail]). */
    NO_VNC_SERVER,
    VNC_START_FAILED, NO_RDP_SERVER, RDP_START_FAILED, RDP_ALREADY_CONFIGURED,
    RDP_KEYRING_LOCKED, RDP_CREDENTIAL_SETUP_UNAVAILABLE, RDP_CERTIFICATE_CHANGED, STREAM_CERTIFICATE_CHANGED,
}

open class RemoteScreenUnavailableException(
    val problem: RemoteScreenProblem,
    /** Extra context from the remote helper, such as the session type or a log tail. */
    val detail: String? = null,
    /** The remote desktop name (for example `GNOME`, `KDE`, `niri`) when the helper knows it. */
    val desktop: String? = null,
) : IllegalStateException("${problem.name}${detail?.let { ": $it" }.orEmpty()}")

class RemoteRdpCertificateChangedException(val expectedSha256: String, val actualSha256: String?) :
    RemoteScreenUnavailableException(RemoteScreenProblem.RDP_CERTIFICATE_CHANGED, "RDP certificate fingerprint changed")

/** `miffan probe` output; see `assets/remote/miffan.sh`. */
@Serializable
data class RemoteMachineProbe(
    val helper: Int,
    val os: String,
    val arch: String,
    val session: Session,
    val cua: Cua,
    val vnc: Vnc,
    val clipboard: String? = null,
    val rdp: Rdp = Rdp(),
) {
    @Serializable
    data class Session(val present: Boolean, val type: String, val desktop: String? = null)

    @Serializable
    data class Cua(val path: String? = null, val version: String? = null, val min: String, val ok: Boolean)

    @Serializable
    data class Rdp(
        val server: String? = null, val version: String? = null, val running: Boolean = false,
        val port: Int? = null, val username: String? = null, val desktop: String? = null,
        val mode: String? = null, val error: String? = null, val log: String? = null,
        val width: Int? = null, val height: Int? = null,
        @SerialName("certificate_sha256") val certificateSha256: String? = null,
    )

    @Serializable
    data class Vnc(
        val server: String? = null,
        val running: Boolean = false,
        val endpoint: String? = null,
        /** Per-start VNC password the helper generated (x11vnc on loopback TCP). */
        val password: String? = null,
        val error: String? = null,
        val session: String? = null,
        val desktop: String? = null,
        val log: String? = null,
    )
}

/** Result of a remote install or upgrade the user confirmed; [output] is the command's tail. */
data class RemoteCommandOutcome(val success: Boolean, val output: String)

/** A desktop viewer bound to a workspace's SSH lease. Call [RemoteDesktopSession.start] once. */
class RemoteScreenConnection internal constructor(
    val hostId: String,
    val platform: RemoteScreenPlatform,
    val workspaceId: String,
    private val handle: LeasedRemote<RemoteDesktopSession>,
    val streamFallback: RemoteStreamFallback? = null,
    private val onClose: (RemoteScreenConnection) -> Unit,
) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    val session: RemoteDesktopSession get() = handle.value
    val certificateSha256 get() = session.certificateSha256

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { handle.close() } finally { onClose(this) }
        }
    }
}

/**
 * Host-level screen configuration and viewer sessions. The screen belongs to the machine, so
 * every workspace on a host shares its settings; connections still go through a workspace so
 * they reuse its verified SSH lease and close when it disconnects.
 */
class RemoteScreenRepository(
    private val workspaces: WorkspaceRepository,
    private val workspaceDao: WorkspaceDAO,
    private val hostDao: RemoteHostDAO,
    private val credentials: RemoteScreenCredentialStore,
    assets: AssetManager,
    context: Context,
) {
    private val streamRoutes = RemoteStreamRoutes(context)
    private val streams = RemoteStreamEngine(RemoteStreamIdentityStore(context), streamRoutes)
    private val helperScript: ByteArray = assets.open(HELPER_ASSET).use { it.readBytes() }
    private val helperVersion: String = Regex("""MIFFAN_HELPER_VERSION=(\d+)""")
        .find(helperScript.toString(Charsets.UTF_8))?.groupValues?.get(1)
        ?: error("Helper script has no version")
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private val open = mutableMapOf<String, MutableSet<RemoteScreenConnection>>()

    suspend fun getConfig(hostId: String): RemoteHostScreenConfig? {
        val host = hostDao.getById(hostId) ?: return null
        val hasPassword = withContext(Dispatchers.IO) { credentials.load(hostId) != null }
        return host.screenConfig(hasPassword)
    }

    /**
     * Saves screen settings. [password] null keeps the stored one; switching to
     * [RemoteScreenAuth.NONE] deletes it. Open viewers on this host are closed.
     */
    suspend fun updateConfig(
        hostId: String,
        enabled: Boolean,
        endpoint: RemoteScreenEndpoint,
        auth: RemoteScreenAuth,
        username: String,
        password: String?,
        protocol: RemoteScreenProtocol? = null,
        rdpUsername: String? = null,
        streamEnabled: Boolean = false,
    ): Boolean {
        if (hostDao.getById(hostId) == null) return false
        val finalUser = username.trim()
        require(finalUser.none { it == '\u0000' || it == '\n' }) { "Invalid screen username" }
        require(password == null || password.isNotEmpty()) { "Password is empty" }
        require(rdpUsername == null || rdpUsername.none { it == '\u0000' || it == '\n' || it == '\r' })
        withContext(Dispatchers.IO) {
            when {
                auth == RemoteScreenAuth.NONE -> credentials.deleteVnc(hostId)
                password != null -> credentials.save(hostId, password)
                credentials.load(hostId) == null -> throw IllegalArgumentException("Screen password required")
            }
        }
        hostDao.updateScreenConfig(hostId, enabled, endpoint.storageValue, auth.storageName, finalUser,
            protocol?.storageName, rdpUsername?.trim(), System.currentTimeMillis(), streamEnabled)
        closeHost(hostId)
        return true
    }

    /** Explicit confirmation/replacement. Helper-attested first pins use a separate conditional write. */
    suspend fun pinRdpCertificate(hostId: String, sha256: String): Boolean {
        val pin = normalizeRdpFingerprint(sha256)
        val updated = hostDao.pinRdpCertificate(hostId, pin, System.currentTimeMillis()) > 0
        if (updated) closeHost(hostId)
        return updated
    }

    suspend fun pinStreamCertificate(hostId: String, sha256: String): Boolean {
        val updated = hostDao.pinStreamCertificate(hostId, normalizeRdpFingerprint(sha256), System.currentTimeMillis()) > 0
        if (updated) closeHost(hostId)
        return updated
    }

    suspend fun streamStatus(hostId: String, expectedRevision: String): RemoteStreamStatus {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        val row = requireNotNull(hostDao.getById(hostId))
        return withStreamRemote(workspaceId) { ssh ->
            ensureRevision(hostId, expectedRevision)
            ensureHelper(ssh)
            try { streams.status(ssh, row, sunshineProbe(ssh)) }
            catch (error: me.rerere.stream.StreamException) {
                if (error.reason == me.rerere.stream.StreamFailureReason.CERTIFICATE_MISMATCH)
                    throw streamCertificateFailure(row.streamCertificateSha256, sunshineProbe(ssh).certificateSha256)
                throw error
            }
        }
    }

    suspend fun startStreamPairing(hostId: String, expectedRevision: String): RemoteStreamPairing {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        val row = requireNotNull(hostDao.getById(hostId))
        val pairingPin = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteStreamPairing>(workspaceId) { ssh ->
            runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { ensureRevision(hostId, expectedRevision) }
            ensureHelper(ssh)
            val probe = sunshineProbe(ssh)
            streams.verifyCertificate(row.streamCertificateSha256, probe)
            check(probe.installed && probe.running) { "Sunshine is not running" }
            val streamHost = streams.host(ssh, row.streamCertificateSha256)
            val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val paired = lifetime.async {
                withTimeout(300_000) {
                    val pin = try { streamHost.pair(pairingPin, "Miffan (${Build.MODEL})".take(64)) }
                    catch (error: me.rerere.stream.StreamException) {
                        if (error.reason == me.rerere.stream.StreamFailureReason.CERTIFICATE_MISMATCH)
                            throw streamCertificateFailure(row.streamCertificateSha256, probe.certificateSha256)
                        throw error
                    }
                    check(hostDao.pinPairedStreamCertificate(hostId, pin, expectedRevision, row.streamCertificateSha256, System.currentTimeMillis()) == 1) {
                        "Host identity or certificate trust changed during pairing"
                    }
                    pin
                }
            }
            object : RemoteStreamPairing {
                override val pin = pairingPin
                override suspend fun await(): String = paired.await()
                override fun close() { lifetime.cancel() }
            }
        }
        return object : RemoteStreamPairing {
            override val pin = pairingPin
            override suspend fun await(): String = try { handle.value.await() } finally { handle.close() }
            override fun close() = handle.close()
        }
    }

    suspend fun enforceStreamEncryption(hostId: String, expectedRevision: String): RemoteCommandOutcome {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        return withStreamRemote(workspaceId) { ssh ->
            ensureRevision(hostId, expectedRevision)
            ensureHelper(ssh)
            val result = ssh.execute("$HELPER sunshine enforce-encryption", timeoutMillis = 30_000)
            val status = parseSunshineCommand(result.stdout)
            RemoteCommandOutcome(status.success && result.exitCode == 0 && !result.timedOut, status.detail)
        }
    }

    private suspend fun ensureRevision(hostId: String, revision: String) {
        if (hostDao.getById(hostId)?.connectionRevision != revision) throw WorkspaceToolTargetChangedException()
    }

    private fun sunshineProbe(ssh: RemoteWorkspaceSession): SunshineProbe {
        val result = ssh.execute("$HELPER sunshine probe", timeoutMillis = 5_000)
        check(result.exitCode == 0 && !result.timedOut) { "Sunshine probe failed" }
        return parseSunshineProbe(result.stdout)
    }

    private fun writeStreamClipboard(ssh: RemoteWorkspaceSession, text: String) {
        val result = ssh.execute("$HELPER clip", timeoutMillis = 10_000,
            stdin = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        check(result.exitCode == 0 && !result.timedOut) { "Remote clipboard command failed" }
    }

    private suspend fun <T> withStreamRemote(workspaceId: String, block: suspend (RemoteWorkspaceSession) -> T): T {
        val callerJob = currentCoroutineContext()[Job]
        var value: Result<T>? = null
        workspaces.openLeasedRemote(workspaceId) { ssh ->
            value = runCatching { runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { block(ssh) } }
            Closeable {}
        }.close()
        return requireNotNull(value).getOrThrow()
    }

    /** Called when the host is deleted; the host row itself is removed by [WorkspaceRepository]. */
    suspend fun forgetHost(hostId: String) {
        closeHost(hostId)
        withContext(Dispatchers.IO) { credentials.delete(hostId); streamRoutes.forget(hostId) }
    }

    /**
     * Opens the screen of the host behind [workspaceId]. The caller binds the session to its
     * scope with start() and closes the connection when done. STREAM preflights before return.
     */
    suspend fun open(
        workspaceId: String,
        sink: RemoteScreenFrameSink,
        jpeg: RfbJpegDecoder?,
        options: RemoteScreenOptions = RemoteScreenOptions(),
    ): RemoteScreenConnection {
        val workspace = workspaceDao.getById(workspaceId)?.takeIf { it.isRemote }
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_FOUND)
        val host = hostDao.getById(requireNotNull(workspace.remoteHostId))
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_FOUND)
        if (!host.screenEnabled) throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_ENABLED)
        val endpoint = RemoteScreenEndpoint.parse(host.screenEndpoint)
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.BAD_ENDPOINT)
        var detected: RemoteScreenPlatform? = null
        var rdpUsername: String? = null
        var streamFallback: RemoteStreamFallback? = null
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteDesktopSession>(workspaceId) { ssh ->
            runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { ensureRevision(host.id, host.connectionRevision) }
            val selection = RemoteScreenProtocol.parse(host.screenProtocol)
            // Manual VNC endpoints keep working without installing a helper or probing a desktop.
            val machine = if (endpoint == RemoteScreenEndpoint.Helper || selection == RemoteScreenProtocol.RDP) {
                ensureHelper(ssh)
                val result = ssh.execute("$HELPER probe", timeoutMillis = 20_000)
                parseHelperProbe(result.stdout)
            } else null
            detected = machine?.let { RemoteScreenPlatform.parse(it.os) }
                ?: RemoteScreenPlatform.parse(host.screenPlatform).takeIf { it != RemoteScreenPlatform.UNKNOWN }
                ?: when (ssh.execute("uname -s", timeoutMillis = 10_000).stdout.trim()) {
                    "Darwin" -> RemoteScreenPlatform.MACOS
                    "Linux" -> RemoteScreenPlatform.LINUX
                    else -> RemoteScreenPlatform.UNKNOWN
                }
            if (host.streamEnabled && detected == RemoteScreenPlatform.LINUX) {
                val attempt = runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                    try {
                        ensureHelper(ssh)
                        val deadline = System.nanoTime() + 20_000_000_000L
                        val probe = sunshineProbe(ssh)
                        val remaining = (deadline - System.nanoTime()) / 1_000_000
                        streams.open(ssh, host, probe, { text -> writeStreamClipboard(ssh, text) }, remaining)
                    } catch (error: RemoteStreamCertificateChangedException) { throw error }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER)) }
                }
                streamFallback = attempt.fallback
                if (attempt.session != null) return@openLeasedRemote attempt.session
            } else if (host.streamEnabled) {
                streamFallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER, "Sunshine requires Linux")
            }
            val protocol = machine?.let { selectDesktopProtocol(selection, it, endpoint) } ?: RemoteDesktopProtocol.VNC
            if (protocol == RemoteDesktopProtocol.RDP) {
                val password = credentials.getOrCreateRdp(host.id)
                val started = startHelperRdp(ssh, password)
                rdpUsername = started.username
                val expectedPin = expectedRdpCertificate(host.rdpCertificateSha256, started.certificateSha256)
                val stream = ssh.openLoopbackStream(requireNotNull(started.port))
                try {
                    val size = if (started.mode == "headless") 1920 to 1080 else
                        (started.width?.takeIf { it in 320..8192 } ?: 1920) to (started.height?.takeIf { it in 240..8192 } ?: 1080)
                    RdpDesktopSession(RdpSession(stream.input, stream.output, stream,
                        RdpCredentials(requireNotNull(started.username), password),
                        RdpOptions(size.first, size.second, expectedPin, rdpSecurityForServer(started.server)), sink), expectedPin,
                        onCertificateVerified = if (host.rdpCertificateSha256 == null && expectedPin != null) { pin ->
                            hostDao.pinFirstRdpCertificate(host.id, pin, host.connectionRevision, System.currentTimeMillis())
                            Unit
                        } else null)
                } catch (error: Throwable) { stream.close(); throw error }
            } else {
                var auth = when (RemoteScreenAuth.parse(host.screenAuth)) {
                    RemoteScreenAuth.NONE -> null
                    RemoteScreenAuth.VNC_PASSWORD -> RfbCredentials(password = credentials.load(host.id) ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.PASSWORD_MISSING))
                    RemoteScreenAuth.MACOS_ACCOUNT -> RfbCredentials(host.screenUsername.ifBlank { host.username },
                        (credentials.load(host.id) ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.PASSWORD_MISSING)))
                }
                val target = when (endpoint) {
                    RemoteScreenEndpoint.Helper -> startHelperVnc(ssh).let { (started, password) ->
                        if (password != null && auth == null) auth = RfbCredentials(password = password)
                        started
                    }
                    else -> endpoint
                }
                val stream = when (target) {
                    is RemoteScreenEndpoint.Tcp -> ssh.openLoopbackStream(target.port)
                    is RemoteScreenEndpoint.Unix -> ssh.openUnixSocketStream(target.path)
                    RemoteScreenEndpoint.Helper -> throw RemoteScreenUnavailableException(RemoteScreenProblem.BAD_ENDPOINT)
                }
                VncDesktopSession(RemoteScreenSession(stream.input, stream.output, stream, auth, jpeg, options, sink))
            }
        }
        return try {
            detected?.takeIf { it != RemoteScreenPlatform.UNKNOWN }?.let { platform ->
                hostDao.updateDetectedScreen(host.id, platform.storageName, rdpUsername)
            }
            val platform = detected ?: RemoteScreenPlatform.parse(host.screenPlatform)
            val connection = RemoteScreenConnection(host.id, platform, workspaceId, handle, streamFallback) { closed ->
                synchronized(lock) {
                    open[host.id]?.remove(closed)
                    if (open[host.id].isNullOrEmpty()) open.remove(host.id)
                }
            }
            synchronized(lock) { open.getOrPut(host.id, ::mutableSetOf) += connection }
            connection
        } catch (error: Throwable) { handle.close(); throw error }
    }

    /** Uses cliprdr for an active RDP session; VNC and STREAM use the SSH clipboard helper. */
    suspend fun setRemoteClipboard(workspaceId: String, @Suppress("UNUSED_PARAMETER") platform: RemoteScreenPlatform, text: String): Boolean {
        val rdp = synchronized(lock) { open.values.flatten().firstOrNull {
            it.workspaceId == workspaceId && it.session.protocol == RemoteDesktopProtocol.RDP
        } }
        if (rdp != null) { rdp.session.sendClipboard(text); return true }
        withRemote(workspaceId) { ssh ->
            ensureHelper(ssh)
            val result = ssh.execute("$HELPER clip", timeoutMillis = 10_000,
                stdin = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            check(result.exitCode == 0) { result.stderr.ifBlank { "Remote clipboard command failed" } }
        }
        return true
    }

    /**
     * [probe] for a host, through any remote workspace on it (the SSH lease is per workspace).
     * [expectedRevision] is the host identity the user was looking at; a changed host is refused.
     */
    suspend fun probeHost(hostId: String, expectedRevision: String): RemoteMachineProbe =
        probe(workspaceOnHost(hostId, expectedRevision))

    /** [installCuaDriver] for a host; see [probeHost]. */
    suspend fun installCuaDriverOnHost(hostId: String, expectedRevision: String, upgradePath: String?): RemoteCommandOutcome =
        installCuaDriver(workspaceOnHost(hostId, expectedRevision), upgradePath)

    private suspend fun workspaceOnHost(hostId: String, expectedRevision: String): String {
        val host = hostDao.getById(hostId) ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_FOUND)
        if (host.connectionRevision != expectedRevision) throw WorkspaceToolTargetChangedException()
        return workspaceDao.getByRemoteHostId(hostId).firstOrNull()?.id
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NO_WORKSPACE)
    }

    /**
     * Starts `cua-driver mcp` inside the remote graphical session (through the helper, which
     * supplies the session environment) and returns its stdio as a leased stream. The caller
     * speaks MCP over it and closes it when done.
     */
    suspend fun openCuaProcess(workspaceId: String): LeasedRemote<me.rerere.workspace.RemoteChannelStream> =
        workspaces.openLeasedRemote(workspaceId) { ssh ->
            ensureHelper(ssh)
            ssh.openProcess("$HELPER cua")
        }

    /** The host behind a remote workspace, for control arbitration keyed by machine. */
    suspend fun hostIdOf(workspaceId: String): String? = workspaceDao.getById(workspaceId)?.remoteHostId

    /** Installs or refreshes the helper and describes the machine behind [workspaceId]. */
    suspend fun probe(workspaceId: String): RemoteMachineProbe = withRemote(workspaceId) { ssh ->
        ensureHelper(ssh)
        val result = ssh.execute("$HELPER probe", timeoutMillis = 20_000)
        check(result.exitCode == 0) { result.stderr.ifBlank { "Remote probe failed" } }
        json.decodeFromString<RemoteMachineProbe>(result.stdout.trim().lineSequence().last())
    }

    /**
     * Installs cua-driver, or upgrades it when [upgradePath] names the installed binary. Runs the
     * vendor's installer on the remote machine, so call it only after the user confirmed.
     */
    suspend fun installCuaDriver(workspaceId: String, upgradePath: String?): RemoteCommandOutcome =
        withRemote(workspaceId) { ssh ->
            val result = ssh.execute(cuaDriverCommand(upgradePath), timeoutMillis = 10 * 60_000L, maxOutputBytes = 256 * 1024)
            RemoteCommandOutcome(result.exitCode == 0 && !result.timedOut,
                (result.stdout + result.stderr).lines().takeLast(30).joinToString("\n"))
        }

    /** Uploads the bundled helper when the remote copy is missing or a different version. */
    private fun ensureHelper(ssh: RemoteWorkspaceSession) {
        val installed = ssh.execute("$HELPER version 2>/dev/null", timeoutMillis = 10_000).stdout.trim()
        if (installed == helperVersion) return
        val result = ssh.execute(
            "mkdir -p \"\$HOME/.miffan/bin\" && cat > \"\$HOME/.miffan/bin/miffan.tmp\" && " +
                "chmod 755 \"\$HOME/.miffan/bin/miffan.tmp\" && mv -f \"\$HOME/.miffan/bin/miffan.tmp\" $HELPER",
            timeoutMillis = 20_000,
            stdin = ByteArrayInputStream(helperScript),
        )
        check(result.exitCode == 0) { result.stderr.ifBlank { "Could not install the Miffan helper" } }
    }

    /** Starts the helper's VNC server; returns where it listens and its password, if any. */
    private fun startHelperVnc(ssh: RemoteWorkspaceSession): Pair<RemoteScreenEndpoint, String?> {
        ensureHelper(ssh)
        val result = ssh.execute("$HELPER vnc start", timeoutMillis = 20_000)
        val status = runCatching {
            json.decodeFromString<RemoteMachineProbe.Vnc>(result.stdout.trim().lineSequence().last())
        }.getOrNull() ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.VNC_START_FAILED, result.stderr.take(500))
        when (status.error) {
            null -> Unit
            "no_graphical_session" -> throw RemoteScreenUnavailableException(RemoteScreenProblem.NO_GRAPHICAL_SESSION)
            "no_vnc_server" -> throw RemoteScreenUnavailableException(RemoteScreenProblem.NO_VNC_SERVER, status.session, status.desktop)
            else -> throw RemoteScreenUnavailableException(RemoteScreenProblem.VNC_START_FAILED, status.log)
        }
        val started = status.endpoint?.let(RemoteScreenEndpoint::parse)?.takeIf { it != RemoteScreenEndpoint.Helper }
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.VNC_START_FAILED, status.endpoint)
        return started to status.password
    }

    private fun startHelperRdp(ssh: RemoteWorkspaceSession, password: String): RemoteMachineProbe.Rdp {
        val result = ssh.execute("$HELPER rdp start", timeoutMillis = 30_000,
            stdin = ByteArrayInputStream((password + "\n").toByteArray(Charsets.UTF_8)))
        val status = parseRdpStart(result.stdout)
        if (result.exitCode != 0 || result.timedOut) throw RemoteScreenUnavailableException(RemoteScreenProblem.RDP_START_FAILED)
        return status
    }

    private suspend fun <T> withRemote(workspaceId: String, block: (RemoteWorkspaceSession) -> T): T {
        var value: Result<T>? = null
        workspaces.openLeasedRemote(workspaceId) { ssh ->
            value = runCatching { block(ssh) }
            Closeable {}
        }.close()
        return requireNotNull(value).getOrThrow()
    }

    private fun closeHost(hostId: String) {
        val connections = synchronized(lock) { open[hostId]?.toList().orEmpty() }
        connections.forEach { runCatching { it.close() } }
    }

    private suspend fun loadPassword(hostId: String): String =
        withContext(Dispatchers.IO) { credentials.load(hostId) }
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.PASSWORD_MISSING)

    companion object {
        /**
         * The exact shell command [installCuaDriver] runs, for the confirmation the user sees:
         * the vendor installer, or `<path> update --apply` when upgrading [upgradePath].
         */
        fun cuaDriverCommand(upgradePath: String?): String {
            val installer = "/bin/bash -c \"\$(curl -fsSL $CUA_INSTALLER)\""
            // cua-driver 0.24's `update` fails its own release check ("Could not reach GitHub")
            // even where `check-update` succeeds, so an upgrade falls back to the installer,
            // which replaces the installed release in place.
            val install = if (upgradePath != null) "${shellQuote(upgradePath)} update --apply || $installer" else installer
            // The installer stops a running daemon; a systemd user service (Restart=on-failure)
            // stays down after that clean stop, so bring it back on the new release.
            return "$install; status=\$?; systemctl --user is-enabled cua-driver.service >/dev/null 2>&1 && " +
                "systemctl --user restart cua-driver.service; exit \$status"
        }

        private const val HELPER_ASSET = "remote/miffan.sh"
        private const val HELPER = "\"\$HOME/.miffan/bin/miffan\""
        private const val CUA_INSTALLER = "https://cua.ai/driver/install.sh"

        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }

    private fun RemoteHostEntity.screenConfig(hasPassword: Boolean) = RemoteHostScreenConfig(
        enabled = screenEnabled,
        endpoint = RemoteScreenEndpoint.parse(screenEndpoint) ?: RemoteScreenEndpoint.Tcp(5900),
        auth = RemoteScreenAuth.parse(screenAuth),
        username = screenUsername,
        platform = RemoteScreenPlatform.parse(screenPlatform),
        hasPassword = hasPassword,
        protocol = RemoteScreenProtocol.parse(screenProtocol), rdpUsername = rdpUsername,
        rdpCertificateSha256 = rdpCertificateSha256,
        streamEnabled = streamEnabled, streamCertificateSha256 = streamCertificateSha256,
    )
}
