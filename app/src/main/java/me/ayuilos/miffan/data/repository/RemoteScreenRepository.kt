package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import android.content.Context
import android.os.Build
import me.rerere.workspace.RemoteRoute
import me.rerere.workspace.relay.RelayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceWindow
import me.rerere.cuaspace.activateWindow
import me.rerere.cuaspace.listWindows
import me.rerere.cuaspace.resizeWindow
import me.rerere.cuaspace.restoreWindow
import me.rerere.cuaspace.windowThumbnail
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.SecureRandom
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    /** Through a relay that offers no screen channel now; [RemoteScreenUnavailableException.detail] is its reason code. */
    RELAY_SCREEN_UNAVAILABLE,
    /** Through a relay, and this computer only has Sunshine, whose UDP picture relays do not carry yet. */
    RELAY_STREAM_ONLY,
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

/** `miffan cua check-update`: cua-driver's own release check. Read-only. */
@Serializable
data class RemoteCuaUpdateCheck(
    @SerialName("current_version") val currentVersion: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("release_notes_url") val releaseNotesUrl: String? = null,
    /** `not_installed`, `check_failed`, or cua-driver's own reason (no network, GitHub refused). */
    val error: String? = null,
)

/**
 * A window of a computer that shares its desktop, with the desktop (macOS Space) it is on. [hidden]
 * means the app runs without showing it (or it is minimized); known only on the desktop in front.
 */
data class RemoteHostWindow(val window: SpaceWindow, val desktop: Long?, val current: Boolean, val hidden: Boolean)

/**
 * A desktop (macOS Space) holding listed windows: its [number] among the display's desktops, or a
 * [fullscreen] app's own; [display] counts from 1 when the computer has several. No number and not
 * full screen: the desktop in front, when the order of desktops is unknown. [current] is the one in
 * front on the computer.
 */
data class RemoteHostDesktop(val id: Long, val current: Boolean, val number: Int? = null, val fullscreen: Boolean = false,
    val app: String? = null, val display: Int? = null)

/**
 * What a computer still needs before it can list windows: GNOME's window-list extension installed
 * ([INSTALL_EXTENSION]), a login for GNOME to load it ([RELOGIN]), or GNOME's switch for all user
 * extensions turned back on by the user ([EXTENSIONS_OFF]; the helper leaves that switch alone).
 */
enum class RemoteWindowSetup { INSTALL_EXTENSION, RELOGIN, EXTENSIONS_OFF }

/**
 * [appsInBackground]: an app here can run showing no window (a Mac), so a window that gives no picture
 * may be one of those, opened again as a Dock click would; elsewhere it is only a window without a picture.
 */
data class RemoteHostWindows(val windows: List<RemoteHostWindow>, val desktops: List<RemoteHostDesktop>,
    val setup: RemoteWindowSetup? = null, val appsInBackground: Boolean = true)

/** How many window pictures a Mac is asked for at once. */
private const val THUMBNAIL_PARALLEL = 3
/** A Linux window's picture in the picker; one takes about a third of a second there. */
private const val LINUX_THUMBNAIL_EDGE = 640
/**
 * A window's picture takes 2–4 seconds on a Mac and longer for one on another desktop, so each gets 10 s
 * on purpose (not the 6 s first planned); an app showing no window never gives one.
 */
private const val THUMBNAIL_TIMEOUT_MILLIS = 10_000L
/** cua's own windows (the agent pointer overlay and the capture service) and the login window. */
private val HIDDEN_WINDOW_APPS = setOf("Cua Driver", "CuaDriver", "cua-spacesd", "Cua Spaces", "loginwindow")

/**
 * Whether [window] is the front window here. [windowId], macOS's number for it once known (see
 * [windowIdOf]), says so exactly; without it, it is found by process, title and frame (cua-driver and
 * cua-spacesd name windows differently), and when its title changed since it was picked (a terminal, a
 * player), by process and frame alone. Another window of the same app in front is not it. A sandboxed
 * app's open or save panel, drawn by another process over the window, counts as the window.
 */
internal fun HostDesktops.frontIs(window: SpaceWindow, windowId: Long? = null): Boolean {
    val front = windows.filter { it.layer == 0 && it.height >= 120 && it.app !in HIDDEN_WINDOW_APPS }.maxByOrNull { it.z } ?: return false
    if (front.pid != window.pid) {
        return front.app in PANEL_APPS && front.x >= window.x && front.y >= window.y &&
            front.x + front.width <= window.x + window.width && front.y + front.height <= window.y + window.height
    }
    if (windowId != null) return front.windowId == windowId
    val mine = find(window.pid, window.title, window.x, window.y, window.width, window.height)
        ?: windows.filter { it.pid == window.pid }.minByOrNull { frameDistance(it.x, it.y, it.width, it.height, window) }
            ?.takeIf { frameDistance(it.x, it.y, it.width, it.height, window) <= FRAME_SLACK }
    return mine === front
}

/**
 * macOS's number for [window] among these windows, when only one of them can be it: its app's window
 * at (nearly) its frame, or the one of those with its title. Null when none is, or when several look
 * alike (two terminals stacked on each other); then [windowIdByOrder] tells them apart.
 */
internal fun HostDesktops.windowIdOf(window: SpaceWindow): Long? {
    val near = windows.filter { it.layer == 0 && it.pid == window.pid && frameDistance(it.x, it.y, it.width, it.height, window) <= TWIN_SLACK }
    val one = near.singleOrNull() ?: near.filter { it.title == window.title }.singleOrNull()
    return one?.windowId
}

/**
 * macOS's number for [window], told apart from look-alikes by its place in the window order:
 * cua-spacesd's [listed] and cua-driver's [all] (every window, taken right after) count the order the
 * same way. Every window of its app must match one of cua-driver's that way (same place in the order,
 * same frame), or the order changed in between and nothing is told: a wrong number would be kept.
 */
internal fun windowIdByOrder(listed: List<SpaceWindow>, all: HostDesktops, window: SpaceWindow): Long? {
    val me = listed.find { it.id == window.id } ?: return null
    fun match(w: SpaceWindow) = all.windows.filter { it.pid == w.pid && it.z == w.zOrder &&
        frameDistance(it.x, it.y, it.width, it.height, w) <= FRAME_SLACK }.singleOrNull()?.windowId
    if (listed.filter { it.pid == me.pid && it.state == SpaceWindow.State.NORMAL }.any { match(it) == null }) return null
    return match(me)
}

private fun frameDistance(x: Double, y: Double, width: Double, height: Double, window: SpaceWindow) =
    kotlin.math.abs(x - window.x) + kotlin.math.abs(y - window.y) + kotlin.math.abs(width - window.width) + kotlin.math.abs(height - window.height)

/** How often finding a shown window's macOS number is tried again after it failed (it costs seconds). */
private const val WINDOW_ID_RETRY_MILLIS = 10_000L
/** How far, in points, a window's frame may be off in the two tools' lists and still be the same window. */
private const val FRAME_SLACK = 8.0
/** Frames this close (summed over position and size) make two windows of an app look alike. */
private const val TWIN_SLACK = 40.0
/** macOS's out-of-process open and save panels of sandboxed apps. */
private val PANEL_APPS = setOf("Open and Save Panel Service", "com.apple.appkit.xpc.openAndSavePanelService")

/** The computer does not share its desktop through cua-spacesd, so single windows cannot be shown. */
class HostWindowsUnavailableException : IllegalStateException("This computer does not share single windows")

/** A desktop viewer bound to a workspace's SSH lease. Call [RemoteDesktopSession.start] once. */
class RemoteScreenConnection internal constructor(
    val hostId: String,
    val platform: RemoteScreenPlatform,
    val workspaceId: String,
    private val handle: LeasedRemote<RemoteDesktopSession>,
    val streamFallback: RemoteStreamFallback? = null,
    val streamRequested: Boolean = false,
    val streamAddress: String? = null,
    /** Who drives this desktop is arbitrated under this key: the host's id, or a Space's own key. */
    val controlKey: String = hostId,
    /** Set when the picture runs through a relay: the relay's pace for it, in kbps (0 = no cap). */
    val relayMediaKbps: Int? = null,
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

/** What names each desktop holding windows (see remoteHostDesktopLabel), current first then Mission Control order. */
internal fun desktopLabels(order: List<List<HostSpace>>?, current: Long?, windows: List<RemoteHostWindow>): List<RemoteHostDesktop> {
    val used = windows.mapNotNull { it.desktop }.toSet()
    if (order == null) {
        return used.sorted().mapIndexed { index, id -> RemoteHostDesktop(id, id == current, number = (index + 1).takeIf { id != current }) }
            .sortedByDescending { it.current }
    }
    val labels = mutableListOf<RemoteHostDesktop>()
    order.forEachIndexed { displayIndex, spaces ->
        val display = (displayIndex + 1).takeIf { order.size > 1 }
        var desktopNumber = 0
        spaces.forEach { space ->
            labels += if (space.fullscreen) {
                val app = windows.firstOrNull { it.desktop == space.id && (space.pid == null || it.window.pid == space.pid) }?.window?.app
                RemoteHostDesktop(space.id, space.id == current, fullscreen = true, app = app?.ifBlank { null }, display = display)
            } else RemoteHostDesktop(space.id, space.id == current, number = ++desktopNumber, display = display)
        }
    }
    return labels.filter { it.id in used }.sortedByDescending { it.current }
}

/** How long Sunshine may take to answer after a restart; Windows needs about 30 s. */
private const val SUNSHINE_RESTART_MILLIS = 60_000L

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
    /** Each computer's own cua-spacesd port and token, read over SSH; memory only. */
    private val hostEndpoints = java.util.concurrent.ConcurrentHashMap<String, HostEndpoint>()
    /** macOS window numbers of shown Mac windows (see hostWindowId), and when finding one last failed. */
    private val hostWindowIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val hostWindowIdMisses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Linux computers whose helper shows single windows (see [LinuxHostWindows]), by workspace. */
    private val linuxWindowHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
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

    suspend fun pairStreamDirectly(hostId: String, expectedRevision: String,
        clientName: String = "Miffan (${Build.MODEL})".take(64),
        interruptActiveStream: Boolean = false): RemoteStreamDirectPairing {
        val workspaceId = workspaceOnHost(hostId, expectedRevision)
        val row = requireNotNull(hostDao.getById(hostId))
        require(clientName.isNotBlank() && clientName.length <= 64 && clientName.none { it.isISOControl() })
        return withStreamRemote(workspaceId) { ssh ->
            ensureRevision(hostId, expectedRevision)
            ensureHelper(ssh)
            val probe = sunshineProbe(ssh)
            streams.verifyCertificate(row.streamCertificateSha256, probe)
            streamDirectPairingPreflight(probe, interruptActiveStream)?.let { return@withStreamRemote it }
            val certificate = requireNotNull(probe.certificateSha256)
            sunshinePairings.pair(ssh, clientName)?.let {
                return@withStreamRemote RemoteStreamDirectPairing.ManualPinNeeded(it)
            }
            // Windows Sunshine probes its encoders for about half a minute before it answers again.
            val confirmed = withTimeoutOrNull(SUNSHINE_RESTART_MILLIS) {
                var paired: Boolean? = null
                while (paired == null) {
                    try { paired = streams.host(ssh, certificate).serverInfo().paired }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: me.rerere.stream.StreamException) {
                        if (error.reason == me.rerere.stream.StreamFailureReason.CERTIFICATE_MISMATCH)
                            throw streamCertificateFailure(certificate, probe.certificateSha256)
                    } catch (_: Exception) { /* Sunshine may still be restarting. */ }
                    if (paired == null) delay(500)
                }
                paired
            } == true
            if (!confirmed) return@withStreamRemote RemoteStreamDirectPairing.ManualPinNeeded(RemoteStreamManualPinReason.NOT_CONFIRMED)
            check(hostDao.pinPairedStreamCertificate(hostId, certificate, expectedRevision, row.streamCertificateSha256, System.currentTimeMillis()) == 1) {
                "Host identity or certificate trust changed during pairing"
            }
            RemoteStreamDirectPairing.Paired(certificate)
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
                withTimeoutOrNull(SUNSHINE_RESTART_MILLIS) {
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
            firewallCheck(ssh).also { check ->
                // The setup page's result also counts as the periodic check.
                if (check.success && check.firewallModelOk) streamRoutes.firewallPassed(hostId, expectedRevision)
                else streamRoutes.firewallFailed(hostId, expectedRevision)
            }
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

    /**
     * The relay media-channel session for a screen over a relay. Relay refusals become
     * [RemoteScreenProblem.RELAY_SCREEN_UNAVAILABLE] with the relay's reason code.
     */
    private suspend fun openRelayMedia(hostId: String, control: RemoteWorkspaceSession): RemoteWorkspaceSession =
        try {
            workspaces.openRelayMediaSession(hostId, control)
                ?: throw RemoteScreenUnavailableException(RemoteScreenProblem.RELAY_SCREEN_UNAVAILABLE, "host_offline")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteScreenUnavailableException) {
            throw error
        } catch (error: Exception) {
            val reason = generateSequence<Throwable>(error) { it.cause }.take(16)
                .filterIsInstance<RelayException>().firstOrNull()?.reason ?: throw error
            throw RemoteScreenUnavailableException(RemoteScreenProblem.RELAY_SCREEN_UNAVAILABLE, reason)
        }

    /**
     * The partner acted on [hostId]'s desktop: open screens of it that stopped refreshing on their
     * own ([RemoteDesktopSession.autoRefreshStopped]) fetch a new picture and resume.
     */
    fun noteComputerAction(hostId: String) {
        val screens = synchronized(lock) { open[hostId]?.toList().orEmpty() }
        screens.forEach { (it.session as? SnapshotDesktopSession)?.noteComputerAction() }
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
        if (workspace.isSpace) return openSpace(workspaceId, host.id, host.name, stream)
        stream.window?.let { return openHostWindow(workspaceId, host.id, it, stream) }
        if (!host.screenEnabled) throw RemoteScreenUnavailableException(RemoteScreenProblem.NOT_ENABLED)
        val configuredEndpoint = RemoteScreenEndpoint.parse(host.screenEndpoint)
        var detected: RemoteScreenPlatform? = null
        var rdpUsername: String? = null
        var streamRequested = stream.requested(host.streamEnabled)
        var streamAddress: String? = null
        var streamFallback: RemoteStreamFallback? = null
        var clipboard: WindowsClipboardChannel? = null
        var relayMediaKbps: Int? = null
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteDesktopSession>(workspaceId) { ssh ->
            runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { ensureRevision(host.id, host.connectionRevision) }
            val windows = ssh.platform == me.rerere.workspace.RemotePlatform.WINDOWS
            // Through a relay: Sunshine's UDP picture cannot follow (protocol v1 has no datagrams), and
            // VNC/RDP bytes go over a second session on the relay's media channel (RELAY_PLAN §4).
            val relayed = ssh.route is RemoteRoute.Relay
            // Every screen through a relay runs on the relay's media channel: open that session while
            // the computer is probed below. Closed again if this screen ends up not using it.
            val earlyMedia = if (relayed) EarlyOpen(callerJob) {
                openRelayMedia(host.id, ssh)
            } else null
            try {
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
            // Through a relay a stream's frames crawl, so a computer with cua-driver shows still pictures.
            if (relayed && (stream.snapshot || windows)) {
                val probed = machine ?: runCatching {
                    ensureHelper(ssh)
                    parseHelperProbe(ssh.execute(screenHelperCommand(ssh.platform, "probe"), timeoutMillis = 20_000).stdout)
                }.getOrNull()
                if (probed?.cua?.ok == true) {
                    val media = runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { requireNotNull(earlyMedia).take() }
                    relayMediaKbps = (media.route as? RemoteRoute.Relay)?.socket?.ready?.pace?.kbps
                    return@openLeasedRemote try {
                        openSnapshot(media, host.name, sink, windows, detected == RemoteScreenPlatform.MACOS, stream.quality.snapshotQuality())
                    } catch (error: Throwable) { media.close(); throw error }
                }
            }
            if (windows && relayed) throw RemoteScreenUnavailableException(RemoteScreenProblem.RELAY_STREAM_ONLY)
            val attempt = if (relayed) {
                StreamOpenResult(fallback = RemoteStreamFallback(RemoteStreamFallbackReason.RELAY).takeIf { streamRequested })
            } else runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                attemptRemoteStream(streamRequested) {
                    if (detected !in setOf(RemoteScreenPlatform.LINUX, RemoteScreenPlatform.MACOS, RemoteScreenPlatform.WINDOWS)) return@attemptRemoteStream StreamOpenResult(
                        fallback = RemoteStreamFallback(RemoteStreamFallbackReason.OTHER, "Sunshine requires Linux, macOS or Windows"))
                    try {
                        ensureHelper(ssh)
                        var deadline = System.nanoTime() + STREAM_OPEN_BUDGET_MILLIS * 1_000_000
                        val initial = sunshineProbe(ssh)
                        if (windows) {
                            if (!initial.installed) throw windowsStreamUnavailable(RemoteStreamFallback(RemoteStreamFallbackReason.SUNSHINE_MISSING))
                            // Inspecting every Windows firewall rule takes seconds; it runs every 48 hours, outside the budget.
                            if (streamRoutes.firewallCheckDue(host.id, host.connectionRevision)) {
                                val started = System.nanoTime()
                                val firewall = firewallCheck(ssh)
                                deadline += System.nanoTime() - started
                                requireSunshineFirewall(firewall)
                                streamRoutes.firewallPassed(host.id, host.connectionRevision)
                            }
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
                            stream.quality.streamConfig(machine?.rdp?.width, machine?.rdp?.height, stream.custom), platform)
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
            val media = if (relayed) runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { requireNotNull(earlyMedia).take() } else null
            val tunnel = media ?: ssh
            relayMediaKbps = (media?.route as? RemoteRoute.Relay)?.socket?.ready?.pace?.kbps
            try {
            if (protocol == RemoteDesktopProtocol.RDP) {
                val password = credentials.getOrCreateRdp(host.id)
                val started = startHelperRdp(ssh, password)
                rdpUsername = started.username
                val expectedPin = expectedRdpCertificate(host.rdpCertificateSha256, started.certificateSha256)
                val stream = tunnel.openLoopbackStream(requireNotNull(started.port))
                val closeStream = Closeable { stream.close(); media?.close() }
                try {
                    val size = if (started.mode == "headless") 1920 to 1080 else
                        (started.width?.takeIf { it in 320..8192 } ?: 1920) to (started.height?.takeIf { it in 240..8192 } ?: 1080)
                    RdpDesktopSession(RdpSession(stream.input, stream.output, closeStream,
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
                    is RemoteScreenEndpoint.Tcp -> tunnel.openLoopbackStream(target.port)
                    is RemoteScreenEndpoint.Unix -> tunnel.openUnixSocketStream(target.path)
                    RemoteScreenEndpoint.Helper -> throw RemoteScreenUnavailableException(RemoteScreenProblem.BAD_ENDPOINT)
                }
                val closeStream = Closeable { stream.close(); media?.close() }
                try {
                    VncDesktopSession(RemoteScreenSession(stream.input, stream.output, closeStream, auth, jpeg, options, sink))
                } catch (error: Throwable) { stream.close(); throw error }
            }
            } catch (error: Throwable) { media?.close(); throw error }
            } finally {
                earlyMedia?.close() // no-op once taken
            }
        }
        return try {
            detected?.takeIf { it != RemoteScreenPlatform.UNKNOWN }?.let { platform ->
                hostDao.updateDetectedScreen(host.id, platform.storageName, rdpUsername)
            }
            val platform = detected ?: RemoteScreenPlatform.parse(host.screenPlatform)
            val connection = RemoteScreenConnection(host.id, platform, workspaceId, handle, streamFallback, streamRequested, streamAddress,
                relayMediaKbps = relayMediaKbps) { closed ->
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

    /**
     * Snapshot mode on the relay's screen channel [media]: cua-driver's MCP for captures and actions,
     * and the helper's `snapshot serve` for the pictures.
     */
    private fun openSnapshot(
        media: RemoteWorkspaceSession, name: String, sink: RemoteScreenFrameSink, windows: Boolean, macos: Boolean,
        quality: SnapshotQuality,
    ): SnapshotDesktopSession {
        // Two exec channels: open them side by side, each costs round trips through the relay.
        val (pictures, process) = openInParallel(
            { media.openProcess(screenHelperCommand(media.platform, "snapshot serve")) },
            { media.openProcess(screenHelperCommand(media.platform, "cua")) },
        )
        return SnapshotDesktopSession(process, pictures, sink, name, macos, windows, quality, onClose = { media.close() })
    }

    /**
     * A partner's own computer: its desktop needs no setup on the host (no VNC, no Sunshine), only the
     * Space's media socket over the host's SSH session.
     */
    private suspend fun openSpace(workspaceId: String, hostId: String, name: String, stream: RemoteStreamRequest): RemoteScreenConnection {
        val (dimension, fps) = when (stream.quality) {
            RemoteScreenQuality.SAVER -> 960 to 15
            RemoteScreenQuality.BALANCED -> 1280 to 30
            else -> 1920 to 30
        }
        val handle = workspaces.openLeasedSpace<RemoteDesktopSession>(workspaceId) { client ->
            val media = runBlocking { client.openMedia(maxDimension = dimension, maxFps = fps) }
            CuaSpaceDesktopSession(client, media, name, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default))
        }
        val connection = RemoteScreenConnection(hostId, RemoteScreenPlatform.LINUX, workspaceId, handle,
            controlKey = spaceControlKey(workspaceId)) { closed ->
            synchronized(lock) {
                open[hostId]?.remove(closed)
                if (open[hostId].isNullOrEmpty()) open.remove(hostId)
            }
        }
        synchronized(lock) { open.getOrPut(hostId, ::mutableSetOf) += connection }
        return connection
    }

    /**
     * Experimental single-window view: the computer's own cua-spacesd streams [window] alone (even while
     * other windows cover it) over the host's SSH session. See [HostDesktopSpace].
     */
    private suspend fun openHostWindow(workspaceId: String, hostId: String, window: SpaceWindow, stream: RemoteStreamRequest): RemoteScreenConnection {
        if (workspaceId in linuxWindowHosts) return openLinuxWindow(workspaceId, hostId, window, stream)
        val (dimension, fps) = when (stream.quality) {
            RemoteScreenQuality.SAVER -> 1280 to 20
            RemoteScreenQuality.BALANCED -> 1600 to 30
            else -> 2400 to 30
        }
        val callerJob = currentCoroutineContext()[Job]
        val handle = workspaces.openLeasedRemote<RemoteDesktopSession>(workspaceId) { ssh ->
            val endpoint = hostEndpoint(workspaceId, ssh)
            val client = HostDesktopSpace.client(ssh, endpoint.port, endpoint.token)
            try {
                val media = runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                    client.openMedia(maxDimension = dimension, maxFps = fps, window = window)
                }
                CuaSpaceDesktopSession(client, media, window.app.ifBlank { window.title },
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default), onClosed = client::close, window = window,
                    // Its windows get the Mac's own scroll direction; spacesd scrolls up for positive deltas.
                    scrollSign = if (endpoint.naturalScroll) -1.0 else 1.0,
                    frontCheck = { hostWindowInFront(workspaceId, window) })
            } catch (error: Throwable) { client.close(); forgetHostEndpoint(workspaceId, error); throw error }
        }
        val connection = RemoteScreenConnection(hostId, RemoteScreenPlatform.MACOS, workspaceId, handle) { closed ->
            synchronized(lock) {
                open[hostId]?.remove(closed)
                if (open[hostId].isNullOrEmpty()) open.remove(hostId)
            }
        }
        synchronized(lock) { open.getOrPut(hostId, ::mutableSetOf) += connection }
        return connection
    }

    /**
     * One window of a Linux computer: the helper's `window serve` records and encodes it, and applies
     * input (bringing it to the front itself), over an SSH channel. See [LinuxHostWindows].
     */
    private suspend fun openLinuxWindow(workspaceId: String, hostId: String, window: SpaceWindow, stream: RemoteStreamRequest): RemoteScreenConnection {
        val (dimension, fps, kbps) = when (stream.quality) {
            RemoteScreenQuality.SAVER -> Triple(1280, 20, 2_500)
            RemoteScreenQuality.BALANCED -> Triple(1600, 30, 5_000)
            else -> Triple(2400, 30, 9_000)
        }
        val handle = workspaces.openLeasedRemote<RemoteDesktopSession>(workspaceId) { ssh ->
            val process = ssh.openProcess(LinuxHostWindows.command("serve", window.id, "$dimension", "$fps", "$kbps"))
            val media = LinuxWindowMedia(process.input, process.output, onClose = process::close)
            CuaSpaceDesktopSession(null, media, window.app.ifBlank { window.title },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default))
        }
        val connection = RemoteScreenConnection(hostId, RemoteScreenPlatform.LINUX, workspaceId, handle) { closed ->
            synchronized(lock) {
                open[hostId]?.remove(closed)
                if (open[hostId].isNullOrEmpty()) open.remove(hostId)
            }
        }
        synchronized(lock) { open.getOrPut(hostId, ::mutableSetOf) += connection }
        return connection
    }

    /**
     * Whether single windows can be shown: a Mac sharing its desktop through cua-spacesd, or a Linux
     * desktop the helper supports.
     */
    suspend fun hostWindowsAvailable(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        // Probed first and recorded after: another screen of this computer may be using what is known
        // meanwhile, and a failed probe (the connection dropped) changes nothing.
        val probed = runCatching {
            withRemote<Any?>(workspaceId) { ssh ->
                HostDesktopSpace.endpoint(ssh)?.let { return@withRemote it }
                if (ssh.platform != me.rerere.workspace.RemotePlatform.POSIX) return@withRemote null
                ensureHelper(ssh)
                LinuxHostWindows.available(ssh)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrElse { return@withContext false }
        synchronized(lock) {
            // Linux is looked up first everywhere, so its mark goes on before a Mac's endpoint goes off.
            when (probed) {
                is HostEndpoint -> { hostEndpoints[workspaceId] = probed; linuxWindowHosts.remove(workspaceId) }
                true -> { linuxWindowHosts += workspaceId; hostEndpoints.remove(workspaceId) }
                else -> { linuxWindowHosts.remove(workspaceId); hostEndpoints.remove(workspaceId) }
            }
        }
        probed is HostEndpoint || probed == true
    }

    /**
     * The windows worth offering and the desktops (macOS Spaces) they are on, in Mission Control order
     * when the Mac tells it. Overlays (cua's own pointer and capture windows) are left out.
     */
    suspend fun hostWindows(workspaceId: String): RemoteHostWindows {
        if (workspaceId in linuxWindowHosts) return withContext(Dispatchers.IO) { withRemote(workspaceId, LinuxHostWindows::list) }
        return macHostWindows(workspaceId)
    }

    private suspend fun macHostWindows(workspaceId: String): RemoteHostWindows = withHostDesktop(workspaceId) { client, ssh ->
        // The window list (about a second on the Mac) and where each window is are read side by side.
        val where = async(Dispatchers.IO) { runCatching { HostDesktopSpace.placement(ssh) }.getOrNull() }
        val windows = client.listWindows()
        val (placed, order) = where.await() ?: (null to null)
        val current = placed?.current
        val hosted = windows.mapNotNull { window ->
            if (window.app in HIDDEN_WINDOW_APPS) return@mapNotNull null
            val found = placed?.find(window.pid, window.title, window.x, window.y, window.width, window.height)
            val spaces = found?.spaces
            // Untitled windows on several desktops at once are overlays; small untitled ones are popups.
            if (window.title.isBlank() && ((spaces?.size ?: 0) > 1 || window.height < 240)) return@mapNotNull null
            if (placed != null && spaces.isNullOrEmpty() && window.state != SpaceWindow.State.MINIMIZED) return@mapNotNull null
            val onCurrent = current?.let { spaces?.contains(it) } ?: window.available
            RemoteHostWindow(
                window = window,
                desktop = spaces?.firstOrNull(),
                current = onCurrent,
                // On the desktop in front, a window that is not on screen is an app running without
                // one (or minimized); elsewhere the picture tells (see RemoteScreenVM).
                hidden = onCurrent && found != null && !found.onScreen,
            )
        }
        RemoteHostWindows(hosted, desktopLabels(order, current, hosted))
    }

    /**
     * Shows a window of [window]'s app, as clicking it in the Dock does, and waits for it. Returns the
     * window to stream: [window] itself when the app showed it again, otherwise the app's newly shown
     * window, or null when none came up.
     */
    suspend fun reopenHostApp(workspaceId: String, window: SpaceWindow): SpaceWindow? = withContext(Dispatchers.IO) {
        // A Linux window is never listed as hidden: it shows when focused.
        if (workspaceId in linuxWindowHosts) return@withContext window
        val placed = withRemote(workspaceId) { ssh -> HostDesktopSpace.reopenApp(ssh, window.pid) }
        val shown = placed?.windows?.filter { it.pid == window.pid && it.onScreen && it.height >= 120 }.orEmpty()
        if (shown.isEmpty()) return@withContext null
        // The same window shown again keeps its target, so it streams without listing again.
        if (shown.any { it.title == window.title && kotlin.math.abs(it.width - window.width) < 2 && kotlin.math.abs(it.height - window.height) < 2 }) {
            return@withContext window.copy(state = SpaceWindow.State.NORMAL)
        }
        hostWindows(workspaceId).windows.map { it.window }
            .filter { it.pid == window.pid && it.state == SpaceWindow.State.NORMAL }
            .let { candidates -> candidates.firstOrNull { it.available } ?: candidates.firstOrNull() }
    }

    /**
     * Small pictures of [windows] over one connection, a few at a time, each as it lands; null when a
     * window gave no picture in time (an app running without showing it gives none). Stops when
     * collection stops.
     */
    fun hostWindowThumbnails(workspaceId: String, windows: List<SpaceWindow>): Flow<Pair<SpaceWindow, ByteArray?>> = channelFlow {
        if (workspaceId in linuxWindowHosts) {
            val job = currentCoroutineContext()[Job]
            withContext(Dispatchers.IO) {
                withRemote(workspaceId) { ssh ->
                    LinuxHostWindows.thumbnails(ssh, windows, LINUX_THUMBNAIL_EDGE, active = { job?.isActive != false }) { window, bytes ->
                        trySendBlocking(window to bytes)
                    }
                }
            }
            return@channelFlow
        }
        withHostDesktop(workspaceId) { client, _ ->
            val gate = Semaphore(THUMBNAIL_PARALLEL)
            windows.map { window ->
                launch {
                    gate.withPermit {
                        send(window to withTimeoutOrNull(THUMBNAIL_TIMEOUT_MILLIS) { client.windowThumbnail(window) })
                    }
                }
            }.joinAll()
        }
    }

    /** GNOME: installs the helper's window-list extension, which GNOME loads at the next login. */
    suspend fun installHostWindowExtension(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        workspaceId in linuxWindowHosts && withRemote(workspaceId, LinuxHostWindows::installExtension)
    }

    /** KDE: grants the helper Plasma's remote desktop permission, after the user agreed on the phone. */
    suspend fun authorizeHostWindows(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        workspaceId in linuxWindowHosts && withRemote(workspaceId, LinuxHostWindows::authorize)
    }

    suspend fun restoreHostWindow(workspaceId: String, window: SpaceWindow) {
        if (workspaceId in linuxWindowHosts) return activateHostWindow(workspaceId, window)
        withHostDesktop(workspaceId) { client, _ -> client.restoreWindow(window) }
    }

    suspend fun activateHostWindow(workspaceId: String, window: SpaceWindow) {
        if (workspaceId in linuxWindowHosts) return withContext(Dispatchers.IO) { withRemote(workspaceId) { LinuxHostWindows.focus(it, window) } }
        withHostDesktop(workspaceId) { client, _ -> client.activateWindow(window) }
    }

    /** The size [window] got, or null when the computer does not say; throws when resizing failed. */
    suspend fun resizeHostWindow(workspaceId: String, window: SpaceWindow, width: Double, height: Double): Pair<Double, Double>? {
        if (workspaceId in linuxWindowHosts) {
            return withContext(Dispatchers.IO) { withRemote(workspaceId) { LinuxHostWindows.resize(it, window, width, height) } }
        }
        return withHostDesktop(workspaceId) { client, _ -> client.resizeWindow(window, width, height) }
    }

    /**
     * Runs [block] with a client of the computer's own cua-spacesd. Its port and token are read once and
     * kept in memory; a failure reads them again next time (the service may have restarted).
     * Cancelling the caller cancels [block]'s calls.
     */
    private suspend fun <T> withHostDesktop(
        workspaceId: String,
        block: suspend CoroutineScope.(SpaceClient, me.rerere.workspace.RemoteWorkspaceSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        val callerJob = currentCoroutineContext()[Job]
        withRemote(workspaceId) { ssh ->
            val client = hostClient(workspaceId, ssh)
            try {
                runBlocking(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) { block(client, ssh) }
            } catch (error: Throwable) {
                forgetHostEndpoint(workspaceId, error)
                throw error
            } finally { client.close() }
        }
    }

    private fun hostEndpoint(workspaceId: String, ssh: me.rerere.workspace.RemoteWorkspaceSession): HostEndpoint =
        hostEndpoints[workspaceId]
            ?: HostDesktopSpace.endpoint(ssh)?.also { hostEndpoints[workspaceId] = it }
            ?: throw HostWindowsUnavailableException()

    private fun hostClient(workspaceId: String, ssh: me.rerere.workspace.RemoteWorkspaceSession): SpaceClient =
        hostEndpoint(workspaceId, ssh).let { HostDesktopSpace.client(ssh, it.port, it.token) }

    /**
     * Whether [window] itself is the front window of the desktop in front, so input lands on it (another
     * window of the same app in front is not); null when the computer cannot tell.
     */
    suspend fun hostWindowInFront(workspaceId: String, window: SpaceWindow): Boolean? = withContext(Dispatchers.IO) {
        // A Linux computer's helper brings the window to the front itself before input.
        if (workspaceId in linuxWindowHosts) return@withContext null
        val shown = runCatching { withRemote(workspaceId) { ssh -> HostDesktopSpace.onScreen(ssh) } }.getOrNull() ?: return@withContext null
        shown.frontIs(window, hostWindowId(workspaceId, window))
    }

    /**
     * macOS's number for [window] (see [windowIdOf]), found once per window and kept: then the front check
     * compares numbers, which tells even two stacked look-alike terminals apart. Null while it cannot be
     * found (the window is on another desktop, or the order kept changing).
     */
    private suspend fun hostWindowId(workspaceId: String, window: SpaceWindow): Long? {
        val key = "$workspaceId/${window.id}/${window.epoch}"
        hostWindowIds[key]?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (hostWindowIdMisses[key] ?: Long.MIN_VALUE / 2) < WINDOW_ID_RETRY_MILLIS) return null
        val found = runCatching {
            // Every window, on any desktop: a look-alike elsewhere must not pass for it.
            withRemote(workspaceId) { ssh -> HostDesktopSpace.allWindows(ssh) }?.windowIdOf(window)
                ?: withHostDesktop(workspaceId) { client, ssh ->
                    // Look-alikes: their place in the window order, which cua-spacesd and cua-driver count alike.
                    val listed = client.listWindows()
                    HostDesktopSpace.allWindows(ssh)?.let { windowIdByOrder(listed, it, window) }
                }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (found == null) hostWindowIdMisses[key] = now else hostWindowIds[key] = found
        return found
    }

    private fun forgetHostEndpoint(workspaceId: String, error: Throwable) {
        if (error !is CancellationException) hostEndpoints.remove(workspaceId)
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

    /** Asks the host's cua-driver whether a newer release exists; see [probeHost]. */
    suspend fun checkCuaUpdateOnHost(hostId: String, expectedRevision: String): RemoteCuaUpdateCheck =
        withRemote(workspaceOnHost(hostId, expectedRevision)) { ssh ->
            ensureHelper(ssh)
            val result = ssh.execute(screenHelperCommand(ssh.platform, "cua check-update"), timeoutMillis = 45_000)
            check(result.exitCode == 0 && !result.timedOut) { result.stderr.ifBlank { "Update check failed" } }
            json.decodeFromString<RemoteCuaUpdateCheck>(result.stdout.trim().lineSequence().last())
        }

    /** [installCuaDriver] for a host; see [probeHost]. */
    suspend fun installCuaDriverOnHost(hostId: String, expectedRevision: String): RemoteCommandOutcome =
        installCuaDriver(workspaceOnHost(hostId, expectedRevision))

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

    /** Whether the workspace is a partner's own computer, whose desktop is not its host's. */
    suspend fun isSpace(workspaceId: String): Boolean = workspaceDao.getById(workspaceId)?.isSpace == true

    /** The cua-driver of a partner's own computer: its MCP endpoint, held over the host's SSH session. */
    suspend fun openSpaceDriver(workspaceId: String): LeasedRemote<SpaceDriverEndpoint> =
        workspaces.openLeasedSpace(workspaceId) { client -> SpaceDriverEndpoint(client.mcpUrl, client.mcpHttpClient()) }

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
     * Installs cua-driver, or updates the installed one, through the helper. Runs the
     * vendor's installer on the remote machine, so call it only after the user confirmed.
     */
    suspend fun installCuaDriver(workspaceId: String): RemoteCommandOutcome =
        withRemote(workspaceId) { ssh ->
            ensureHelper(ssh)
            val command = cuaDriverCommand(ssh.platform)
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
         * The exact command [installCuaDriver] runs, for the confirmation the user sees: the helper's
         * `cua install`, which installs or updates cua-driver through the computer's own proxy.
         */
        fun cuaDriverCommand(platform: me.rerere.workspace.RemotePlatform = me.rerere.workspace.RemotePlatform.POSIX): String =
            screenHelperCommand(platform, "cua install")

        private const val HELPER = "\"\$HOME/.miffan/bin/miffan\""
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

/** A partner's own computer's desktop is not its host's: user and partner arbitrate it on its own. */
fun spaceControlKey(workspaceId: String): String = "space:$workspaceId"

/** Where a Space's cua-driver answers MCP; nothing to release beyond the SSH lease that holds it. */
class SpaceDriverEndpoint(val url: String, val http: okhttp3.OkHttpClient) : java.io.Closeable {
    override fun close() {}
}
