package me.ayuilos.miffan.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.repository.RemoteTerminalConnection
import me.ayuilos.miffan.data.repository.WorkspaceRepository

internal data class RemoteTerminalIdentity(
    val hostId: String,
    val revision: String,
    val hostName: String,
    val endpoint: String,
    val remoteRoot: String,
) {
    fun sameTarget(other: RemoteTerminalIdentity): Boolean =
        hostId == other.hostId && revision == other.revision && remoteRoot == other.remoteRoot
}

internal sealed interface RemoteTerminalUiState {
    data object Connecting : RemoteTerminalUiState
    data object Connected : RemoteTerminalUiState
    data class Disconnected(val exitStatus: Int) : RemoteTerminalUiState
    data class Failed(val error: Throwable) : RemoteTerminalUiState
    data object TargetChanged : RemoteTerminalUiState
    data object HostMissing : RemoteTerminalUiState
}

internal sealed interface RemoteTerminalCommand {
    data class Write(val bytes: ByteArray) : RemoteTerminalCommand
    data class Resize(val columns: Int, val rows: Int) : RemoteTerminalCommand
}

/** One reader and one writer remain alive while pages detach; the screen never owns a View. */
internal object RemoteTerminalRegistry {
    internal class Session(
        val workspaceId: String,
        val identity: RemoteTerminalIdentity,
        val screen: RemoteTerminalScreen = RemoteTerminalScreen(),
    ) {
        val _state = MutableStateFlow<RemoteTerminalUiState>(RemoteTerminalUiState.Connecting)
        val state = _state.asStateFlow()
        val queue = Channel<RemoteTerminalCommand>(Channel.UNLIMITED)
        var job: Job? = null
        var connection: RemoteTerminalConnection? = null

        fun write(bytes: ByteArray) {
            if (_state.value == RemoteTerminalUiState.Connected) queue.trySend(RemoteTerminalCommand.Write(bytes))
        }

        fun resize(columns: Int, rows: Int) {
            if (_state.value == RemoteTerminalUiState.Connected) queue.trySend(RemoteTerminalCommand.Resize(columns, rows))
        }

        fun close() {
            queue.close()
            screen.sendBytes = {}
            job?.cancel()
            connection?.close()
        }
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, Session>()

    fun getOrOpen(workspaceId: String, identity: RemoteTerminalIdentity,
        repository: WorkspaceRepository, columns: Int, rows: Int): Session {
        synchronized(lock) {
            sessions[workspaceId]?.let { current ->
                if (current.identity.sameTarget(identity)) return current
                sessions.remove(workspaceId)
                scope.launch { current.close() }
            }
            val next = Session(workspaceId, identity)
            next.screen.sendBytes = next::write
            sessions[workspaceId] = next
            next.job = scope.launch {
                var connection: RemoteTerminalConnection? = null
                var writer: Job? = null
                try {
                    connection = repository.openRemoteTerminal(
                        id = workspaceId, columns = columns, rows = rows,
                        expectedHostId = identity.hostId,
                        expectedHostRevision = identity.revision,
                        expectedRemoteRoot = identity.remoteRoot,
                    )
                    if (!isCurrent(next)) return@launch
                    next.connection = connection
                    next._state.value = RemoteTerminalUiState.Connected
                    writer = launch {
                        try {
                            for (command in next.queue) {
                                when (command) {
                                    is RemoteTerminalCommand.Write -> {
                                        connection.output.write(command.bytes)
                                        connection.output.flush()
                                    }
                                    is RemoteTerminalCommand.Resize -> connection.resize(command.columns, command.rows)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            if (isCurrent(next)) next._state.value = RemoteTerminalUiState.Failed(error)
                            connection.close()
                        }
                    }
                    next.resize(columns, rows)
                    val buffer = ByteArray(8192)
                    while (isActive && isCurrent(next)) {
                        val count = connection.input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            val bytes = buffer.copyOf(count)
                            withContext(Dispatchers.Main.immediate) {
                                if (isCurrent(next)) next.screen.appendOutput(bytes)
                            }
                        }
                    }
                    if (isCurrent(next) && next._state.value == RemoteTerminalUiState.Connected) {
                        next._state.value = RemoteTerminalUiState.Disconnected(connection.exitStatus)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isCurrent(next)) next._state.value = RemoteTerminalUiState.Failed(error)
                } finally {
                    next.queue.close()
                    writer?.cancel()
                    connection?.close()
                    next.connection = null
                }
            }
            return next
        }
    }

    fun restart(workspaceId: String, identity: RemoteTerminalIdentity,
        repository: WorkspaceRepository, columns: Int, rows: Int): Session {
        close(workspaceId)
        return getOrOpen(workspaceId, identity, repository, columns, rows)
    }

    fun close(workspaceId: String) {
        val old = synchronized(lock) { sessions.remove(workspaceId) }
        old?.let { scope.launch { it.close() } }
    }

    private fun isCurrent(session: Session): Boolean = synchronized(lock) {
        sessions[session.workspaceId] === session
    }
}
