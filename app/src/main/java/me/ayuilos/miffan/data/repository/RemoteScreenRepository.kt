package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
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
    VNC_START_FAILED,
}

class RemoteScreenUnavailableException(
    val problem: RemoteScreenProblem,
    /** Extra context from the remote helper, such as the session type or a log tail. */
    val detail: String? = null,
    /** The remote desktop name (for example `GNOME`, `KDE`, `niri`) when the helper knows it. */
    val desktop: String? = null,
) : IllegalStateException("${problem.name}${detail?.let { ": $it" }.orEmpty()}")

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
) {
    @Serializable
    data class Session(val present: Boolean, val type: String, val desktop: String? = null)

    @Serializable
    data class Cua(val path: String? = null, val version: String? = null, val min: String, val ok: Boolean)

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

/** A VNC viewer bound to a workspace's SSH lease. Call [RemoteScreenSession.start] once. */
class RemoteScreenConnection internal constructor(
    val hostId: String,
    val platform: RemoteScreenPlatform,
    private val handle: LeasedRemote<RemoteScreenSession>,
    private val onClose: (RemoteScreenConnection) -> Unit,
) : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    val session: RemoteScreenSession get() = handle.value

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
) {
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
    ): Boolean {
        val host = hostDao.getById(hostId) ?: return false
        val finalUser = username.trim()
        require(finalUser.none { it == '\u0000' || it == '\n' }) { "Invalid screen username" }
        require(password == null || password.isNotEmpty()) { "Password is empty" }
        withContext(Dispatchers.IO) {
            when {
                auth == RemoteScreenAuth.NONE -> credentials.delete(hostId)
                password != null -> credentials.save(hostId, password)
                credentials.load(hostId) == null -> throw IllegalArgumentException("Screen password required")
            }
        }
        hostDao.update(host.copy(
            screenEnabled = enabled,
            screenEndpoint = endpoint.storageValue,
            screenAuth = auth.storageName,
            screenUsername = finalUser,
            updatedAt = System.currentTimeMillis(),
        ))
        closeHost(hostId)
        return true
    }

    /** Called when the host is deleted; the host row itself is removed by [WorkspaceRepository]. */
    suspend fun forgetHost(hostId: String) {
        closeHost(hostId)
        withContext(Dispatchers.IO) { credentials.delete(hostId) }
    }

    /**
     * Opens the screen of the host behind [workspaceId]. The session is not started; the caller
     * starts it on its own scope and closes the connection when done.
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
        val rfbCredentials = when (RemoteScreenAuth.parse(host.screenAuth)) {
            RemoteScreenAuth.NONE -> null
            RemoteScreenAuth.VNC_PASSWORD -> RfbCredentials(password = loadPassword(host.id))
            RemoteScreenAuth.MACOS_ACCOUNT -> RfbCredentials(
                username = host.screenUsername.ifBlank { host.username },
                password = loadPassword(host.id),
            )
        }
        var detected: RemoteScreenPlatform? = null
        val handle = workspaces.openLeasedRemote(workspaceId) { ssh ->
            if (RemoteScreenPlatform.parse(host.screenPlatform) == RemoteScreenPlatform.UNKNOWN) {
                detected = when (ssh.execute("uname -s", timeoutMillis = 10_000).stdout.trim()) {
                    "Darwin" -> RemoteScreenPlatform.MACOS
                    "Linux" -> RemoteScreenPlatform.LINUX
                    else -> RemoteScreenPlatform.UNKNOWN
                }
            }
            var credentials = rfbCredentials
            val target = when (endpoint) {
                RemoteScreenEndpoint.Helper -> startHelperVnc(ssh).let { (started, password) ->
                    // A helper-generated password replaces "no auth"; macOS keeps the account.
                    if (password != null && credentials == null) credentials = RfbCredentials(password = password)
                    started
                }
                else -> endpoint
            }
            val stream = when (target) {
                is RemoteScreenEndpoint.Tcp -> ssh.openLoopbackStream(target.port)
                is RemoteScreenEndpoint.Unix -> ssh.openUnixSocketStream(target.path)
                RemoteScreenEndpoint.Helper -> throw RemoteScreenUnavailableException(RemoteScreenProblem.BAD_ENDPOINT)
            }
            RemoteScreenSession(stream.input, stream.output, stream, credentials, jpeg, options, sink)
        }
        detected?.takeIf { it != RemoteScreenPlatform.UNKNOWN }?.let { platform ->
            hostDao.getById(host.id)?.let { hostDao.update(it.copy(screenPlatform = platform.storageName)) }
        }
        val platform = detected ?: RemoteScreenPlatform.parse(host.screenPlatform)
        val connection = RemoteScreenConnection(host.id, platform, handle) { closed ->
            synchronized(lock) {
                open[host.id]?.remove(closed)
                if (open[host.id].isNullOrEmpty()) open.remove(host.id)
            }
        }
        synchronized(lock) { open.getOrPut(host.id, ::mutableSetOf) += connection }
        return connection
    }

    /**
     * Puts [text] on the remote clipboard over SSH so it can be pasted with the platform
     * shortcut; VNC key events cannot carry most non-Latin text. The helper finds the graphical
     * session (Wayland, X11 or macOS) and its clipboard tool.
     */
    suspend fun setRemoteClipboard(workspaceId: String, @Suppress("UNUSED_PARAMETER") platform: RemoteScreenPlatform, text: String): Boolean {
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
    )
}
