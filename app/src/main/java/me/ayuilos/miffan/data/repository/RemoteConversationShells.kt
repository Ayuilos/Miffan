package me.ayuilos.miffan.data.repository

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.workspace.PersistentRemoteShell
import me.rerere.workspace.RemoteTerminalCommandSpec
import me.rerere.workspace.WorkspaceCommandResult

data class RemoteConversationShellInfo(
    val sessionId: String,
    val target: WorkspaceToolTargetSnapshot,
    val activeCommand: RemoteConversationShellCommand? = null,
)

data class RemoteConversationShellCommand(val id: String, val command: String, val interactive: Boolean)

data class RemoteConversationCommandResult(
    val result: WorkspaceCommandResult,
    val sessionId: String,
    val reused: Boolean,
    val sessionOpen: Boolean,
)

class RemoteConversationShellHandle internal constructor(
    val info: RemoteConversationShellInfo,
    private val connection: RemoteTerminalConnection,
    onClosed: () -> Unit,
) : Closeable {
    internal val shell = PersistentRemoteShell(connection.input, connection.output, connection::close, onClosed)
    val isOpen: Boolean get() = shell.isOpen && connection.isConnected
    fun inputWriter(): (ByteArray) -> Unit = shell.inputWriter()
    fun resize(columns: Int, rows: Int) { connection.resize(columns, rows) }
    override fun close() { shell.close() }
}

/** Scoped to a chat, never to a shared assistant or SSH host. Both AI and user tools use this pool. */
internal class RemoteConversationShells(
    private val open: suspend (WorkspaceToolTargetSnapshot) -> RemoteTerminalConnection,
    private val validate: suspend (WorkspaceToolTargetSnapshot) -> Unit,
) {
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<String, RemoteConversationShellHandle>()
    private val _states = MutableStateFlow<Map<String, RemoteConversationShellInfo>>(emptyMap())
    val states = _states.asStateFlow()

    suspend fun execute(
        target: WorkspaceToolTargetSnapshot,
        command: String,
        cwd: String?,
        defaultCwd: String?,
        timeoutMillis: Long?,
        onOutput: suspend (ByteArray) -> Unit = {},
        onReady: suspend (RemoteConversationShellHandle) -> Unit = {},
    ): RemoteConversationCommandResult = withContext(Dispatchers.IO) {
        val conversationId = requireNotNull(target.conversationId) { "A saved conversation is required for a persistent terminal" }
        val (handle, reused) = mutex.withLock {
            try {
                validate(target)
            } catch (error: Exception) {
                sessions[conversationId]?.takeIf { it.info.target.sameTarget(target) }?.close()
                throw error
            }
            val existing = sessions[conversationId]
            if (existing != null && existing.isOpen && existing.info.target.sameTarget(target)) {
                existing to true
            } else {
                existing?.close()
                val connection = open(target)
                val info = RemoteConversationShellInfo(UUID.randomUUID().toString(), target)
                val next = RemoteConversationShellHandle(info, connection) {
                    sessions.computeIfPresent(conversationId) { _, handle ->
                        handle.takeUnless { it.info.sessionId == info.sessionId }
                    }
                    _states.update { current ->
                        if (current[conversationId]?.sessionId == info.sessionId) current - conversationId else current
                    }
                }
                sessions[conversationId] = next
                if (!next.isOpen) sessions.remove(conversationId, next)
                _states.update { if (next.isOpen) it + (conversationId to info) else it - conversationId }
                next to false
            }
        }
        val directory = cwd?.let {
            RemoteTerminalCommandSpec.workingDirectory(requireNotNull(target.remoteRoot), it)
        }
        val activeCommand = RemoteConversationShellCommand(UUID.randomUUID().toString(), command, timeoutMillis == null)
        val result = try {
            handle.shell.execute(
                command, directory, timeoutMillis, onOutput,
                onReady = { onReady(handle) },
                beforeDispatch = {
                    // A command queued behind an interactive prompt must be checked again when
                    // it actually reaches the shell, not just when it joined the queue.
                    try {
                        validate(target)
                        check(handle.isOpen) { "The terminal session ended before this command could start" }
                        _states.update { current ->
                            val info = current[conversationId]
                            if (info?.sessionId == handle.info.sessionId) {
                                current + (conversationId to info.copy(activeCommand = activeCommand))
                            } else current
                        }
                    } catch (error: Exception) {
                        handle.close()
                        throw error
                    }
                },
                initialCwd = defaultCwd?.let {
                    RemoteTerminalCommandSpec.workingDirectory(requireNotNull(target.remoteRoot), it)
                },
            )
        } finally {
            _states.update { current ->
                val info = current[conversationId]
                if (info?.sessionId == handle.info.sessionId && info.activeCommand?.id == activeCommand.id) {
                    current + (conversationId to info.copy(activeCommand = null))
                } else current
            }
        }
        RemoteConversationCommandResult(result, handle.info.sessionId, reused, handle.isOpen)
    }

    suspend fun close(conversationId: String, expectedSessionId: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            sessions[conversationId]?.takeIf { expectedSessionId == null || it.info.sessionId == expectedSessionId }?.let {
                sessions.remove(conversationId)
                it.close()
            }
        }
    }

    suspend fun closeAssistant(assistantId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = sessions.filterValues { it.info.target.assistantId == assistantId }.keys.toList()
            ids.forEach { sessions.remove(it)?.close() }
        }
    }
}
