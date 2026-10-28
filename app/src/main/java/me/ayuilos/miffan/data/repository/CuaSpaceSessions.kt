package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceStream
import me.rerere.cuaspace.SpaceStreamOpener
import me.rerere.workspace.RemoteWorkspaceFiles
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.SpaceWorkspaceFiles

/** What `miffan space info` prints: where the Space's cua-spacesd listens on the host, and its token. */
@Serializable
internal data class CuaSpaceInfo(
    val id: String? = null,
    val state: String? = null,
    val port: Int? = null,
    val token: String? = null,
    val error: String? = null,
    val detail: String? = null,
) {
    override fun toString(): String = "CuaSpaceInfo(id=$id, state=$state, port=$port, error=$error)"
}

class CuaSpaceUnavailableException(val code: String, detail: String?) :
    IllegalStateException(detail?.takeIf { it.isNotBlank() }?.let { "$code: $it" } ?: code)

/**
 * The cua Space behind each SPACE workspace, reached through the workspace's SSH connection to the
 * host. The port and token come from the host's helper every time a client is built (the port
 * changes on each Space start) and live only in memory, never in the database or backups.
 */
class CuaSpaceSessions internal constructor(assets: AssetManager) {
    private val helper = RemoteMiffanHelper(assets)

    private class Cached(val ssh: RemoteWorkspaceSession, val client: SpaceClient) {
        @Volatile var verifiedAt: Long = System.currentTimeMillis()
    }

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Blocking; call inside the workspace's remote operation. */
    internal fun files(workspace: WorkspaceEntity, ssh: RemoteWorkspaceSession): RemoteWorkspaceFiles =
        SpaceWorkspaceFiles(client(workspace, ssh))

    internal fun client(workspace: WorkspaceEntity, ssh: RemoteWorkspaceSession): SpaceClient {
        val name = requireNotNull(workspace.spaceName) { "Workspace ${workspace.id} has no Space" }
        cache[workspace.id]?.let { cached ->
            if (cached.ssh === ssh && ssh.isConnected) {
                if (System.currentTimeMillis() - cached.verifiedAt < VERIFY_AFTER_MILLIS) return cached.client
                // The Space may have restarted on a new port since; a failed health check reconnects.
                if (runCatching { runBlocking(Dispatchers.IO) { cached.client.health() } }.isSuccess) {
                    cached.verifiedAt = System.currentTimeMillis()
                    return cached.client
                }
            }
            cache.remove(workspace.id, cached)
            cached.client.close()
        }
        val info = info(ssh, name)
        val client = SpaceClient(opener(ssh), requireNotNull(info.port), requireNotNull(info.token))
        cache[workspace.id] = Cached(ssh, client)
        return client
    }

    internal fun ensureHelper(ssh: RemoteWorkspaceSession) = helper.ensureInstalled(ssh)

    fun forget(workspaceId: String) {
        cache.remove(workspaceId)?.client?.close()
    }

    private fun info(ssh: RemoteWorkspaceSession, name: String): CuaSpaceInfo {
        require(SPACE_NAME.matches(name)) { "Invalid Space name" }
        helper.ensureInstalled(ssh)
        val result = ssh.execute("$HELPER space info $name", timeoutMillis = INFO_TIMEOUT_MILLIS)
        val info = result.stdout.trim().lineSequence().lastOrNull()
            ?.let { runCatching { JsonInstant.decodeFromString<CuaSpaceInfo>(it) }.getOrNull() }
        if (info == null || info.error != null || info.port == null || info.token.isNullOrEmpty()) {
            throw CuaSpaceUnavailableException(info?.error ?: "space_info_failed",
                info?.detail ?: result.stderr.trim().take(300))
        }
        return info
    }

    private fun opener(ssh: RemoteWorkspaceSession) = SpaceStreamOpener { port, timeoutMillis ->
        val channel = ssh.openLoopbackStream(port, timeoutMillis)
        object : SpaceStream {
            override val input: InputStream get() = channel.input
            override val output: OutputStream get() = channel.output
            override val isConnected: Boolean get() = channel.isConnected
            override fun close() = channel.close()
        }
    }

    companion object {
        private const val HELPER = "\"\$HOME/.miffan/bin/miffan\""
        private const val VERIFY_AFTER_MILLIS = 30_000L
        // Starting a stopped Space boots the runtime VM on macOS first.
        private const val INFO_TIMEOUT_MILLIS = 180_000L
        val SPACE_NAME = Regex("miffan-[0-9a-f]{8}")
    }
}
