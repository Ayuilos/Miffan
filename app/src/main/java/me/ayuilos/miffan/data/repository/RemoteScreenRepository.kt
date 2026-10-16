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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

enum class RemoteScreenPlatform { UNKNOWN, MACOS, LINUX, WINDOWS;
    val storageName: String get() = if (this == UNKNOWN) "" else name.lowercase()
    companion object {
        fun parse(value: String): RemoteScreenPlatform = entries.firstOrNull { it.storageName == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Windows computer control remains unsupported until P7c. Screen viewing uses Sunshine. */
class WindowsRemoteScreenUnsupportedException : RemoteScreenUnavailableException(RemoteScreenProblem.WINDOWS_NOT_SUPPORTED)

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
    WINDOWS_NOT_SUPPORTED, FIREWALL_REVIEW_NEEDED, FIREWALL_CHECK_FAILED, STREAM_UNAVAILABLE,
}

open class RemoteScreenUnavailableException(
    val problem: RemoteScreenProblem,
    /** Extra context from the remote helper, such as the session type or a log tail. */
    val detail: String? = null,
    /** The remote desktop name (for example `GNOME`, `KDE`, `niri`) when the helper knows it. */
    val desktop: String? = null,
    val firewallCheck: RemoteSunshineFirewallCheck? = null,
    val streamFailure: RemoteStreamFallback? = null,
) : IllegalStateException("${problem.name}${detail?.let { ": $it" }.orEmpty()}")

class RemoteRdpCertificateChangedException(val expectedSha256: String, val actualSha256: String?) :
    RemoteScreenUnavailableException(RemoteScreenProblem.RDP_CERTIFICATE_CHANGED, "RDP certificate fingerprint changed")

/** `miffan probe` output; see `remote-helper/src/miffan.sh`. */
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
    val hostname: String? = null,
) {
    @Serializable
    data class Session(val present: Boolean, val type: String, val desktop: String? = null,
        /** Windows only: `locked`, `unlocked` or `unknown`. */
        @SerialName("lock_state") val lockState: String? = null)

    @Serializable
    data class Cua(
        val path: String? = null, val version: String? = null, val min: String, val ok: Boolean,
        @SerialName("autostart_run_level") val autostartRunLevel: String? = null,
        @SerialName("daemon_session_ids") val daemonSessionIds: List<Int> = emptyList(),
        @SerialName("daemon_integrity_rids") val daemonIntegrityRids: List<Int> = emptyList(),
        @SerialName("bridge_available") val bridgeAvailable: Boolean = false,
        @SerialName("permission_review_needed") val permissionReviewNeeded: Boolean = false,
    )

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
    val streamRequested: Boolean = false,
    val streamAddress: String? = null,
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
    private val streams = RemoteStreamEngine(RemoteStreamIdentityStore(context), streamRoutes, context.applicationContext)
    private val helper = RemoteMiffanHelper(assets)
    private val sunshinePairings = RemoteSunshineUnpair(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    /** The long-lived clipboard writer of each open Windows screen, by workspace. */
    private val windowsClipboards = java.util.concurrent.ConcurrentHashMap<String, WindowsClipboardChannel>()
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
            protocol?.storageName, rdpUsername?.trim(), System.currentTimeMillis())
        closeHost(hostId)
        return true
    }

    /**
     * Turns the screen on or off without touching its saved settings: a computer without a display
     * (a server, a NAS) is still used through commands. Open viewers on this host are closed.
     */
    suspend fun setScreenEnabled(hostId: String, enabled: Boolean): Boolean {
        val updated = hostDao.setScreenEnabled(hostId, enabled, System.currentTimeMillis()) > 0
        if (updated) closeHost(hostId)
        return updated
    }

    /** Updates the independent streaming preference without replacing screen settings or certificate pins. */
    suspend fun setStreamEnabled(hostId: String, enabled: Boolean): Boolean {
        val updated = hostDao.setStreamEnabled(hostId, enabled, System.currentTimeMillis()) > 0
        if (updated) closeHost(hostId)
        return updated
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
            try {
                val probe = sunshineProbe(ssh)
                streams.status(ssh, row, probe).also { status ->
                    // Paired on the computer but never recorded here: record it rather than pair again.
                    if (status.paired && row.streamCertificateSha256 == null) {
                        val certificate = checkNotNull(probe.certificateSha256)
                        check(hostDao.pinPairedStreamCertificate(hostId, certificate, expectedRevision, null, System.currentTimeMillis()) == 1) {
                            "Host identity or certificate trust changed while checking the pairing"
                        }
                    }
                }
            }
            catch (error: me.rerere.stream.StreamException) {
                if (error.reason == me.rerere.stream.StreamFailureReason.CERTIFICATE_MISMATCH)
                    throw streamCertificateFailure(row.streamCertificateSha256, sunshineProbe(ssh).certificateSha256)
                throw error
            }
        }
    }

    suspend fun startStreamPairing(hostId: String, expectedRevision: String,
        clientName: String = "Miffan (${Build.MODEL})".take(64)): RemoteStreamPairing {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        val row = requireNotNull(hostDao.getById(hostId))
        require(clientName.isNotBlank() && clientName.length <= 64 && clientName.none { it.isISOControl() })
        val pairingPin = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteStreamPairing>(workspaceId) { ssh ->
            runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { ensureRevision(hostId, expectedRevision) }
            ensureHelper(ssh)
            val probe = sunshineProbe(ssh)
            streams.verifyCertificate(row.streamCertificateSha256, probe)
            check(probe.installed && probe.running) { "Sunshine is not running" }
            val pairingCertificate = if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS)
                requireNotNull(probe.certificateSha256) { "Windows Sunshine public certificate unavailable" }
                else row.streamCertificateSha256
            // Sunshine 2026.914 and older keep a second record when a phone pairs again, then refuse it as
            // ambiguous at the last step; start from none. Best effort: newer Sunshine replaces it anyway.
            val cleared = runCatching { sunshinePairings.unpair(ssh) }.getOrNull()
            if (cleared?.restarted == true) runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                withTimeoutOrNull(20_000) {
                    while (runCatching { streams.host(ssh, null).serverInfo() }.isFailure) delay(500)
                }
            }
            val streamHost = streams.host(ssh, pairingCertificate)
            val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val paired = lifetime.async {
                withTimeout(300_000) {
                    val pin = try { streamHost.pair(pairingPin, clientName) }
                    catch (error: me.rerere.stream.StreamException) {
                        if (error.reason == me.rerere.stream.StreamFailureReason.CERTIFICATE_MISMATCH)
                            throw streamCertificateFailure(pairingCertificate, probe.certificateSha256)
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

    suspend fun startSunshine(hostId: String, expectedRevision: String): RemoteCommandOutcome =
        sunshineCommand(hostId, expectedRevision, "start")

    suspend fun openSunshinePermissionSettings(hostId: String, expectedRevision: String,
        permission: RemoteSunshinePermission): RemoteCommandOutcome = sunshineCommand(hostId, expectedRevision,
        "open-settings " + when (permission) {
            RemoteSunshinePermission.SCREEN_RECORDING -> "screen"
            RemoteSunshinePermission.ACCESSIBILITY -> "accessibility"
        })

    suspend fun enforceStreamEncryption(hostId: String, expectedRevision: String): RemoteCommandOutcome =
        sunshineCommand(hostId, expectedRevision, "enforce-encryption")

    private suspend fun sunshineCommand(hostId: String, expectedRevision: String, command: String): RemoteCommandOutcome {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        return withStreamRemote(workspaceId) { ssh ->
            ensureRevision(hostId, expectedRevision)
            ensureHelper(ssh)
            if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS && command.startsWith("open-settings"))
                return@withStreamRemote RemoteCommandOutcome(false, "Sunshine permission settings are only applicable to macOS")
            val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine $command"), timeoutMillis = 30_000)
            val status = parseSunshineCommand(result.stdout)
            RemoteCommandOutcome(status.success && result.exitCode == 0 && !result.timedOut, status.detail)
        }
    }

    /** Read-only. The suggested plan must be displayed and approved before calling apply. */
    suspend fun checkSunshineFirewall(hostId: String, expectedRevision: String): RemoteSunshineFirewallCheck =
        withStreamRemote(workspaceOnHost(hostId, expectedRevision)) { ssh ->
            ensureRevision(hostId, expectedRevision)
            require(ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) { "Windows firewall only" }
            ensureHelper(ssh)
            firewallCheck(ssh)
        }

    /** Executes exactly the plan the user approved. Never synthesizes/retries a replacement plan. */
    suspend fun applySunshineFirewall(hostId: String, expectedRevision: String,
        plan: ApprovedSunshineFirewallPlan): RemoteSunshineFirewallResult =
        withStreamRemote(workspaceOnHost(hostId, expectedRevision)) { ssh ->
            ensureRevision(hostId, expectedRevision)
            require(ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) { "Windows firewall only" }
            ensureHelper(ssh)
            val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine firewall apply"),
                timeoutMillis = 60_000, stdin = ByteArrayInputStream(plan.json.toByteArray(Charsets.UTF_8)))
            parseSunshineFirewallResult(result.stdout).let {
                it.copy(success = it.success && result.exitCode == 0 && !result.timedOut)
            }
        }

    private fun firewallCheck(ssh: RemoteWorkspaceSession): RemoteSunshineFirewallCheck {
        val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine firewall check"), timeoutMillis = 30_000)
        if (result.timedOut)
            throw RemoteScreenUnavailableException(RemoteScreenProblem.FIREWALL_CHECK_FAILED, "Windows firewall inspection timed out")
        val check = try { parseSunshineFirewallCheck(result.stdout) }
        catch (error: Exception) { throw RemoteScreenUnavailableException(RemoteScreenProblem.FIREWALL_CHECK_FAILED, "Invalid firewall inspection result") }
        return check.copy(success = check.success && result.exitCode == 0)
    }

    private suspend fun ensureRevision(hostId: String, revision: String) {
        if (hostDao.getById(hostId)?.connectionRevision != revision) throw WorkspaceToolTargetChangedException()
    }

    private fun sunshineProbe(ssh: RemoteWorkspaceSession): SunshineProbe {
        val result = ssh.execute(screenHelperCommand(ssh.platform, "sunshine probe"), timeoutMillis = 15_000)
        check(result.exitCode == 0 && !result.timedOut) { "Sunshine probe failed" }
        return parseSunshineProbe(result.stdout)
    }

    private fun writeStreamClipboard(ssh: RemoteWorkspaceSession, text: String) {
        val result = ssh.execute(screenHelperCommand(ssh.platform, "clip"),
            timeoutMillis = if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) 30_000 else 10_000,
            stdin = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        check(result.exitCode == 0 && !result.timedOut) { "Remote clipboard command failed" }
        if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) {
            val status = parseWindowsClipboard(result.stdout)
            check(status.success && status.verified) { status.detail }
        }
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
        stream: RemoteStreamRequest = RemoteStreamRequest(),
    ): RemoteScreenConnection {
        val workspace = workspaceDao.getById(workspaceId)?.takeIf { it.isRemote }
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_FOUND)
        val host = hostDao.getById(requireNotNull(workspace.remoteHostId))
            ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_FOUND)
        if (!host.screenEnabled) throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_ENABLED)
        val configuredEndpoint = RemoteScreenEndpoint.parse(host.screenEndpoint)
        var detected: RemoteScreenPlatform? = null
        var rdpUsername: String? = null
        var streamRequested = stream.requested(host.streamEnabled)
        var streamAddress: String? = null
        var streamFallback: RemoteStreamFallback? = null
        var clipboard: WindowsClipboardChannel? = null
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteDesktopSession>(workspaceId) { ssh ->
            runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { ensureRevision(host.id, host.connectionRevision) }
            val windows = ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS
            if (windows) clipboard = WindowsClipboardChannel { ssh.openProcess(screenHelperCommand(ssh.platform, "clip serve")) }
            streamRequested = windows || streamRequested
            val endpoint = if (windows) RemoteScreenEndpoint.Helper else configuredEndpoint
                ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.BAD_ENDPOINT)
            val selection = RemoteScreenProtocol.parse(host.screenProtocol)
            // Manual VNC endpoints keep working without installing a helper or probing a desktop.
            val machine = if (windows || endpoint == RemoteScreenEndpoint.Helper || selection == RemoteScreenProtocol.RDP) {
                ensureHelper(ssh)
                val result = ssh.execute(screenHelperCommand(ssh.platform, "probe"), timeoutMillis = 20_000)
                parseHelperProbe(result.stdout)
            } else null
            detected = if (windows) RemoteScreenPlatform.WINDOWS else machine?.let { RemoteScreenPlatform.parse(it.os) }
                ?: RemoteScreenPlatform.parse(host.screenPlatform).takeIf { it != RemoteScreenPlatform.UNKNOWN }
                ?: when (ssh.execute("uname -s", timeoutMillis = 10_000).stdout.trim()) {
                    "Darwin" -> RemoteScreenPlatform.MACOS
                    "Linux" -> RemoteScreenPlatform.LINUX
                    else -> RemoteScreenPlatform.UNKNOWN
                }
            val attempt = runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                attemptRemoteStream(streamRequested) {
                    if (detected !in setOf(RemoteScreenPlatform.LINUX, RemoteScreenPlatform.MACOS, RemoteScreenPlatform.WINDOWS)) return@attemptRemoteStream StreamOpenResult(
                        fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER, "Sunshine requires Linux, macOS or Windows"))
                    try {
                        ensureHelper(ssh)
                        val deadline = System.nanoTime() + 20_000_000_000L
                        val initial = sunshineProbe(ssh)
                        if (windows) {
                            val firewall = firewallCheck(ssh)
                            if (!initial.installed) throw windowsStreamUnavailable(RemoteStreamFallback(RemoteStreamFallbackReason.SUNSHINE_MISSING))
                            requireSunshineFirewall(firewall)
                        }
                        streams.verifyCertificate(host.streamCertificateSha256, initial)
                        val platform = requireNotNull(detected)
                        val probe = prepareStreamProbe(platform, initial) {
                            val result = ssh.execute("$HELPER sunshine wake", timeoutMillis = 10_000)
                            if (result.exitCode == 0 && !result.timedOut) parseSunshineCommand(result.stdout)
                            else SunshineCommand(false, "Display wake failed", true)
                        }
                        val remaining = (deadline - System.nanoTime()) / 1_000_000
                        val opened = streams.open(ssh, host, probe, { text -> if (clipboard?.write(text) != true) writeStreamClipboard(ssh, text) }, remaining,
                            stream.quality.streamConfig(machine?.rdp?.width, machine?.rdp?.height), platform)
                        if (platform == RemoteScreenPlatform.MACOS && opened.fallback != null) {
                            // A capture denial can appear only after attempting to build a stream.
                            val diagnostic = try { sunshineProbe(ssh) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { return@attemptRemoteStream opened }
                            streams.verifyCertificate(host.streamCertificateSha256, diagnostic)
                            opened.copy(fallback = streamDiagnosticFallback(diagnostic, opened.fallback))
                        } else opened
                    } catch (error: RemoteStreamCertificateChangedException) { throw error }
                    catch (error: RemoteScreenUnavailableException) { throw error }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER)) }
                }
            }
            streamFallback = attempt.fallback
            streamAddress = attempt.address
            if (attempt.session != null) return@openLeasedRemote attempt.session
            if (windows) throw windowsStreamUnavailable(attempt.fallback)
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
            val connection = RemoteScreenConnection(host.id, platform, workspaceId, handle, streamFallback, streamRequested, streamAddress) { closed ->
                synchronized(lock) {
                    open[host.id]?.remove(closed)
                    if (open[host.id].isNullOrEmpty()) open.remove(host.id)
                }
                clipboard?.let { windowsClipboards.remove(workspaceId, it); it.close() }
            }
            clipboard?.let { windowsClipboards.put(workspaceId, it)?.close() }
            synchronized(lock) { open.getOrPut(host.id, ::mutableSetOf) += connection }
            connection
        } catch (error: Throwable) { clipboard?.close(); handle.close(); throw error }
    }

    /** Uses cliprdr for an active RDP session; VNC and STREAM use the SSH clipboard helper. */
    /**
     * Starts the Windows cua bridge ahead of typing so the first clipboard write is fast too.
     * Best effort and quick to skip elsewhere: the screen never waits on it.
     */
    suspend fun prepareWindowsTyping(workspaceId: String) {
        // An open Windows screen opens its clipboard channel, which also starts the bridge.
        windowsClipboards[workspaceId]?.let { channel -> withContext(Dispatchers.IO) { channel.prepare() }; return }
        withRemote(workspaceId) { ssh ->
            if (ssh.platform != me.rerere.workspace.RemotePlatform.WINDOWS) return@withRemote
            ensureHelper(ssh)
            ssh.execute(screenHelperCommand(ssh.platform, "cua warm"), timeoutMillis = 30_000)
        }
    }

    suspend fun setRemoteClipboard(workspaceId: String, @Suppress("UNUSED_PARAMETER") platform: RemoteScreenPlatform, text: String): Boolean {
        val rdp = synchronized(lock) { open.values.flatten().firstOrNull {
            it.workspaceId == workspaceId && it.session.protocol == RemoteDesktopProtocol.RDP
        } }
        if (rdp != null) { rdp.session.sendClipboard(text); return true }
        withRemote(workspaceId) { ssh ->
            ensureHelper(ssh)
            writeStreamClipboard(ssh, text)
        }
        return true
    }

    /**
     * Presses a Mac's Mission Control shortcut (`desktop-left`, `desktop-right`, `mission-control`,
     * `app-windows`) through cua-driver over SSH, for a stream whose keys macOS does not match
     * against system shortcuts. False when the Mac has no cua-driver.
     */
    suspend fun pressMacShortcut(workspaceId: String, name: String): Boolean = withRemote(workspaceId) { ssh ->
        requireScreenSupported(ssh)
        ensureHelper(ssh)
        val result = ssh.execute("$HELPER mac-shortcut $name", timeoutMillis = 15_000)
        if (result.exitCode == 127) return@withRemote false
        check(result.exitCode == 0) { result.stderr.ifBlank { "Mac shortcut failed" } }
        true
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
            ssh.openProcess(screenHelperCommand(ssh.platform, "cua"))
        }

    /** The host behind a remote workspace, for control arbitration keyed by machine. */
    suspend fun hostIdOf(workspaceId: String): String? = workspaceDao.getById(workspaceId)?.remoteHostId

    /** Installs or refreshes the helper and describes the machine behind [workspaceId]. */
    suspend fun probe(workspaceId: String): RemoteMachineProbe {
        val host = hostIdOf(workspaceId)?.let { hostDao.getById(it) }
        val probe = withRemote(workspaceId) { ssh ->
            ensureHelper(ssh)
            val result = ssh.execute(screenHelperCommand(ssh.platform, "probe"), timeoutMillis = 20_000)
            check(result.exitCode == 0 && !result.timedOut) { result.stderr.ifBlank { "Remote probe failed" } }
            json.decodeFromString<RemoteMachineProbe>(result.stdout.trim().lineSequence().last())
        }
        if (host != null && host.screenPlatform.isBlank() && probe.os in setOf("macos", "linux", "windows")) {
            hostDao.updateProbedScreenPlatform(host.id, host.connectionRevision, probe.os)
        }
        return probe
    }

    /**
     * Installs cua-driver, or upgrades it when [upgradePath] names the installed binary. Runs the
     * vendor's installer on the remote machine, so call it only after the user confirmed.
     */
    suspend fun installCuaDriver(workspaceId: String, upgradePath: String?): RemoteCommandOutcome =
        withRemote(workspaceId) { ssh ->
            if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) ensureHelper(ssh)
            val command = cuaDriverCommand(upgradePath, ssh.platform)
            val result = ssh.execute(command, timeoutMillis = 10 * 60_000L, maxOutputBytes = 256 * 1024)
            RemoteCommandOutcome(result.exitCode == 0 && !result.timedOut,
                (result.stdout + result.stderr).lines().takeLast(30).joinToString("\n"))
        }

    private fun requireScreenSupported(ssh: RemoteWorkspaceSession) {
        if (ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS) throw WindowsRemoteScreenUnsupportedException()
    }

    /** Uploads the bundled helper when the remote copy is missing or a different version. */
    private fun ensureHelper(ssh: RemoteWorkspaceSession) = helper.ensureInstalled(ssh)

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
        fun cuaDriverCommand(upgradePath: String?, platform: me.rerere.workspace.RemotePlatform = me.rerere.workspace.RemotePlatform.POSIX): String {
            if (platform == me.rerere.workspace.RemotePlatform.WINDOWS) return screenHelperCommand(platform, "cua install")
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

        private const val HELPER = "\"\$HOME/.miffan/bin/miffan\""
        private const val CUA_INSTALLER = "https://cua.ai/driver/install.sh"

        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }

    private fun RemoteHostEntity.screenConfig(hasPassword: Boolean) = RemoteHostScreenConfig(
        enabled = screenEnabled,
        endpoint = RemoteScreenEndpoint.parse(screenEndpoint) ?: RemoteScreenEndpoint.Tcp(5900),
        auth = RemoteScreenAuth.parse(screenAuth),
        username = screenUsername,
        platform = if (platform == "windows") RemoteScreenPlatform.WINDOWS else RemoteScreenPlatform.parse(screenPlatform),
        hasPassword = hasPassword,
        protocol = RemoteScreenProtocol.parse(screenProtocol), rdpUsername = rdpUsername,
        rdpCertificateSha256 = rdpCertificateSha256,
        streamEnabled = streamEnabled, streamCertificateSha256 = streamCertificateSha256,
    )
}
