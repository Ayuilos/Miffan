package me.ayuilos.miffan.data.repository

import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
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

class RemoteScreenUnavailableException(message: String) : IllegalStateException(message)

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
) {
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
            ?: throw RemoteScreenUnavailableException("Remote workspace not found")
        val host = hostDao.getById(requireNotNull(workspace.remoteHostId))
            ?: throw RemoteScreenUnavailableException("Remote host not found")
        if (!host.screenEnabled) throw RemoteScreenUnavailableException("Screen is not enabled for this host")
        val endpoint = RemoteScreenEndpoint.parse(host.screenEndpoint)
            ?: throw RemoteScreenUnavailableException("Invalid screen endpoint")
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
            val stream = when (endpoint) {
                is RemoteScreenEndpoint.Tcp -> ssh.openLoopbackStream(endpoint.port)
                is RemoteScreenEndpoint.Unix -> ssh.openUnixSocketStream(endpoint.path)
            }
            RemoteScreenSession(stream.input, stream.output, stream, rfbCredentials, jpeg, options, sink)
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
     * shortcut; VNC key events cannot carry most non-Latin text. Returns false where the
     * platform has no supported clipboard command yet.
     */
    suspend fun setRemoteClipboard(workspaceId: String, platform: RemoteScreenPlatform, text: String): Boolean {
        val command = when (platform) {
            RemoteScreenPlatform.MACOS -> "pbcopy"
            // Linux needs the graphical session's environment; the P2 launcher script provides it.
            else -> return false
        }
        workspaces.openLeasedRemote(workspaceId) { ssh ->
            val result = ssh.execute(command, timeoutMillis = 10_000,
                stdin = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            check(result.exitCode == 0) { "Remote clipboard command failed" }
            Closeable {}
        }.close()
        return true
    }

    private fun closeHost(hostId: String) {
        val connections = synchronized(lock) { open[hostId]?.toList().orEmpty() }
        connections.forEach { runCatching { it.close() } }
    }

    private suspend fun loadPassword(hostId: String): String =
        withContext(Dispatchers.IO) { credentials.load(hostId) }
            ?: throw RemoteScreenUnavailableException("Screen password missing")

    private fun RemoteHostEntity.screenConfig(hasPassword: Boolean) = RemoteHostScreenConfig(
        enabled = screenEnabled,
        endpoint = RemoteScreenEndpoint.parse(screenEndpoint) ?: RemoteScreenEndpoint.Tcp(5900),
        auth = RemoteScreenAuth.parse(screenAuth),
        username = screenUsername,
        platform = RemoteScreenPlatform.parse(screenPlatform),
        hasPassword = hasPassword,
    )
}
