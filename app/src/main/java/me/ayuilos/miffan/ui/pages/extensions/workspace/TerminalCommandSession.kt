package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.R
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.data.repository.RemoteConversationShellHandle
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.workspace.RemoteTerminalCommandSpec

internal enum class TerminalCommandState { READY, CONNECTING, RUNNING, FINISHED }

/** Process-owned sessions survive leaving the chat/terminal. Receipts prevent reruns after death. */
internal object TerminalCommandSessions {
    private val sessions = mutableMapOf<String, TerminalCommandSession>()

    fun getOrCreate(
        id: String,
        context: Context,
        scope: CoroutineScope,
        repository: WorkspaceRepository,
        target: WorkspaceToolTargetSnapshot,
        command: RemoteTerminalCommandSpec,
        onResult: (String) -> Unit,
    ): TerminalCommandSession = sessions.getOrPut(id) {
        TerminalCommandSession(id, context.applicationContext, scope, repository, target, command, onResult)
    }.also { it.attachResultHandler(onResult) }

    fun forget(id: String) {
        sessions[id]?.takeIf { it.state.value == TerminalCommandState.FINISHED || it.state.value == TerminalCommandState.READY }?.let {
            it.release()
            sessions.remove(id)
        }
    }
}

internal class TerminalCommandSession(
    private val id: String,
    context: Context,
    private val scope: CoroutineScope,
    private val repository: WorkspaceRepository,
    val target: WorkspaceToolTargetSnapshot,
    val command: RemoteTerminalCommandSpec,
    private var onResult: ((String) -> Unit)?,
    val screen: RemoteTerminalScreen = RemoteTerminalScreen(),
) {
    private val receipts = context.getSharedPreferences("terminal_command_receipts", Context.MODE_PRIVATE)
    private val saved = receipts.getString(id, null)
    private val _state = MutableStateFlow(if (saved == null) TerminalCommandState.READY else TerminalCommandState.FINISHED)
    val state = _state.asStateFlow()
    private val queue = Channel<RemoteTerminalCommand>(64)
    private var job: Job? = null
    private var connection: RemoteConversationShellHandle? = null
    private var columns = 80
    private var rows = 24
    private var reported = false
    private val capture = TerminalOutputCapture()
    var result: String? = saved?.let {
        if (it == "started") terminalCommandResult("interrupted", null, "", false,
            "The app stopped while this command was active. Its remote outcome is unknown; inspect state before retrying.") else it
    }
        private set

    init {
        screen.appendOutput(("$ ${command.command.replace("\n", "\r\n")}\r\n\r\n${context.getString(R.string.terminal_command_session_enter_hint)}\r\n").toByteArray())
        // Terminal protocol replies must never count as the user's Enter key.
        screen.sendBytes = { if (state.value == TerminalCommandState.RUNNING) enqueue(it) }
    }

    fun reportRecoveredResult() {
        if (!reported && result != null) {
            reported = true
            val payload = Json.parseToJsonElement(requireNotNull(result)).jsonObject
            onResult?.invoke(JsonObject(payload + ("terminalRequestId" to JsonPrimitive(id))).toString())
            onResult = null
            // Finished commands retain only their receipt, not a terminal scrollback per call.
            TerminalCommandSessions.forget(id)
        }
    }

    fun attachResultHandler(handler: (String) -> Unit) {
        onResult = handler
        reported = false
    }

    fun write(bytes: ByteArray) {
        when (state.value) {
            TerminalCommandState.READY -> when {
                bytes.contentEquals(byteArrayOf(13)) || bytes.contentEquals(byteArrayOf(10)) -> start()
                bytes.contentEquals(byteArrayOf(3)) -> cancel()
            }
            TerminalCommandState.RUNNING -> enqueue(bytes)
            else -> Unit
        }
    }

    private fun enqueue(bytes: ByteArray) {
        if (queue.trySend(RemoteTerminalCommand.Write(bytes.copyOf())).isFailure) cancel()
    }

    fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        if (state.value == TerminalCommandState.RUNNING) queue.trySend(RemoteTerminalCommand.Resize(columns, rows))
    }

    private fun start() {
        if (state.value != TerminalCommandState.READY) return
        _state.value = TerminalCommandState.CONNECTING
        job = scope.launch {
            var writer: Job? = null
            try {
                // Commit the receipt before opening a channel that can execute remote code.
                check(withContext(Dispatchers.IO) { receipts.edit().putString(id, "started").commit() }) {
                    "Could not save terminal execution receipt"
                }
                val outcome = repository.executeInConversationTerminal(
                    target = target, command = command.command,
                    cwd = command.cwd.takeIf { it.isNotBlank() }, defaultCwd = target.conversationCwd,
                    timeoutMillis = null,
                    onOutput = { bytes -> withContext(Dispatchers.Main) {
                        capture.append(bytes)
                        screen.appendOutput(bytes)
                    } },
                    onReady = { opened ->
                        connection = opened
                        opened.resize(columns, rows)
                        val writeInput = opened.inputWriter()
                        withContext(Dispatchers.Main) { _state.value = TerminalCommandState.RUNNING }
                        writer = scope.launch(Dispatchers.IO) {
                            try {
                                for (event in queue) when (event) {
                                    is RemoteTerminalCommand.Write -> writeInput(event.bytes)
                                    is RemoteTerminalCommand.Resize -> opened.resize(event.columns, event.rows)
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                opened.close()
                            }
                        }
                    },
                )
                val exitCode = outcome.result.exitCode.takeIf { it >= 0 }
                finish(terminalCommandResult(if (exitCode == null) "interrupted" else "completed", exitCode,
                    capture.text(), capture.truncated,
                    if (exitCode == null) "Session ended without an exit status. Remote outcome is unknown; verify before retrying." else null,
                    sessionId = outcome.sessionId, sessionReused = outcome.reused, sessionOpen = outcome.sessionOpen))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finish(terminalCommandResult("interrupted", null, capture.text(), capture.truncated,
                    "${error.message ?: "Terminal failed"}. Remote outcome may be unknown; verify before retrying."))
            } finally {
                queue.close()
                writer?.cancel()
                // A completed command leaves the shared shell alive for subsequent AI calls.
                connection = null
            }
        }
    }

    fun cancel() {
        if (state.value == TerminalCommandState.FINISHED) return
        val started = state.value != TerminalCommandState.READY
        finish(terminalCommandResult(if (started) "interrupted" else "cancelled", null,
            capture.text(), capture.truncated, if (started) "User closed the terminal. A remote process may still be running; inspect state before retrying." else "User cancelled before execution."))
        val opened = connection
        job?.cancel()
        scope.launch(Dispatchers.IO) { opened?.close() }
    }

    private fun finish(value: String) {
        if (state.value == TerminalCommandState.FINISHED) return
        result = value
        receipts.edit().putString(id, value).apply()
        _state.value = TerminalCommandState.FINISHED
        reportRecoveredResult()
    }

    fun release() { onResult = null; screen.sendBytes = {}; queue.close() }
}
