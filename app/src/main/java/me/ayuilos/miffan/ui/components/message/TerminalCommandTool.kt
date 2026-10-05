package me.ayuilos.miffan.ui.components.message

import me.ayuilos.miffan.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.ui.ChainOfThoughtScope
import me.ayuilos.miffan.ui.pages.extensions.workspace.RemoteTerminalKeyBar
import me.ayuilos.miffan.ui.pages.extensions.workspace.RemoteTerminalView
import me.ayuilos.miffan.ui.pages.extensions.workspace.TerminalCommandSession
import me.ayuilos.miffan.ui.pages.extensions.workspace.TerminalCommandSessions
import me.ayuilos.miffan.ui.pages.extensions.workspace.TerminalCommandState
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.workspace.RemoteTerminalCommandSpec
import org.koin.compose.koinInject

internal val LocalTerminalConversationId = staticCompositionLocalOf<String?> { null }

@Composable
internal fun ChainOfThoughtScope.TerminalCommandToolStep(
    tool: UIMessagePart.Tool,
    onToolAnswer: ((String, String) -> Unit)?,
    onToolApproval: ((String, Boolean, String) -> Unit)?,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = koinInject<AppScope>()
    val args = remember(tool.input) { runCatching { tool.inputAsJson().jsonObject }.getOrNull() }
    fun arg(name: String) = runCatching { args?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
    val command = arg("command")
    val cwd = arg("cwd")
    val spec = remember(command, cwd) { runCatching { RemoteTerminalCommandSpec(command, cwd) }.getOrNull() }
    val target = tool.workspaceTarget
    val conversationId = LocalTerminalConversationId.current
    val shellStates by repository.conversationShellStates.collectAsState()
    val uiScope = rememberCoroutineScope()
    val requestId = tool.terminalRequestId
    val pending = tool.isPending && !tool.isExecuted
    var showTerminal by remember(requestId) { mutableStateOf(false) }
    val session = remember(requestId, pending, conversationId) {
        if (pending && requestId != null && target != null && target.conversationId == conversationId &&
            conversationId != null && spec != null && onToolAnswer != null) {
            TerminalCommandSessions.getOrCreate(requestId, context, scope, repository, target, spec) { result ->
                showTerminal = false
                onToolAnswer(tool.toolCallId, result)
            }
        } else null
    }
    val state by (session?.state ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow(TerminalCommandState.FINISHED)
    }).collectAsState()
    LaunchedEffect(session) { session?.reportRecoveredResult() }
    DisposableEffect(session, requestId) {
        onDispose {
            if (session?.state?.value == TerminalCommandState.READY && requestId != null) {
                TerminalCommandSessions.forget(requestId)
            }
        }
    }
    LaunchedEffect(pending, requestId) {
        if (!pending && requestId != null) TerminalCommandSessions.forget(requestId)
    }
    var expanded by remember { mutableStateOf(true) }
    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = { Text(when {
            tool.isExecuted -> stringResource(R.string.terminal_command_title_result)
            tool.approvalState is ToolApprovalState.Denied -> stringResource(R.string.terminal_command_title_cancelled)
            tool.approvalState is ToolApprovalState.Answered -> stringResource(R.string.terminal_command_title_answered)
            !pending -> stringResource(R.string.terminal_command_title_preparing)
            state == TerminalCommandState.RUNNING -> stringResource(R.string.terminal_command_title_running)
            state == TerminalCommandState.CONNECTING -> stringResource(R.string.terminal_command_title_connecting)
            state == TerminalCommandState.FINISHED && session != null -> stringResource(R.string.terminal_command_title_returning)
            else -> stringResource(R.string.terminal_command_title_waiting)
        }, style = MaterialTheme.typography.titleSmall) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(arg("reason"), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.terminal_command_target, target?.remoteHostLabel.orEmpty(), target?.remoteRoot.orEmpty(), cwd.ifBlank { stringResource(R.string.terminal_command_cwd_inherited) }),
                    style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(command, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .verticalScroll(rememberScrollState()).padding(12.dp))
                }
                if (pending) {
                    if (session == null) Text(stringResource(R.string.terminal_command_missing_session))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showTerminal = true }, enabled = session != null && state != TerminalCommandState.FINISHED) {
                            Text(if (state == TerminalCommandState.READY) stringResource(R.string.terminal_command_open) else stringResource(R.string.terminal_command_view))
                        }
                        TextButton(onClick = {
                            if (session != null) session.cancel()
                            else onToolApproval?.invoke(tool.toolCallId, false, "用户取消终端执行")
                        }, enabled = onToolApproval != null || session != null) { Text(stringResource(R.string.terminal_command_cancel_run)) }
                    }
                    Text(stringResource(R.string.terminal_command_enter_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (tool.isExecuted) {
                    val raw = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val result = remember(raw) { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
                    val sessionId = result?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    val active = shellStates[conversationId]?.takeIf { it.sessionId == sessionId }
                    if (sessionId != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (active != null) stringResource(R.string.terminal_command_session_connected) else stringResource(R.string.terminal_command_session_ended),
                                style = MaterialTheme.typography.labelSmall)
                            if (active != null && conversationId != null) TextButton(onClick = {
                                uiScope.launch { repository.closeConversationTerminal(conversationId, active.sessionId) }
                            }) { Text(stringResource(R.string.terminal_command_end_session)) }
                        }
                    }
                    val status = result?.get("status")?.jsonPrimitive?.contentOrNull
                    Text(when (status) {
                        "completed" -> stringResource(R.string.terminal_command_result_completed, result["exitCode"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "cancelled" -> stringResource(R.string.terminal_command_result_cancelled)
                        "interrupted" -> stringResource(R.string.terminal_command_result_interrupted)
                        else -> stringResource(R.string.terminal_command_result_generic)
                    }, style = MaterialTheme.typography.labelMedium)
                    val output = result?.get("output")?.jsonPrimitive?.contentOrNull
                    val error = result?.get("error")?.jsonPrimitive?.contentOrNull
                    val display = listOfNotNull(output?.takeIf { it.isNotBlank() }, error).joinToString("\n")
                        .ifBlank { if (result == null) raw else stringResource(R.string.terminal_command_no_output) }
                    SelectionContainer {
                        Text(display, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (result?.get("truncated")?.jsonPrimitive?.contentOrNull == "true") {
                        Text(stringResource(R.string.terminal_command_output_truncated), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
    if (showTerminal && session != null) TerminalCommandDialog(session) { showTerminal = false }
}

@Composable
private fun TerminalCommandDialog(session: TerminalCommandSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = remember(session) { RemoteTerminalView(context) }
    val state by session.state.collectAsState()
    LaunchedEffect(state) { if (state == TerminalCommandState.FINISHED) onBack() }
    DisposableEffect(view, session) {
        view.screen = session.screen
        view.sendBytes = session::write
        view.onSizeInCellsChanged = session::resize
        onDispose {
            view.sendBytes = {}
            view.onSizeInCellsChanged = { _, _ -> }
            view.detachScreen()
        }
    }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MiffanTheme(colorMode = ColorMode.DARK) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(session.target.workspaceName) }, navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.terminal_command_back_to_chat)) }
                }, actions = { TextButton(onClick = session::cancel) { Text(stringResource(R.string.terminal_command_end)) } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().background(Color.Black)) {
                    Text(when (state) {
                        TerminalCommandState.READY -> stringResource(R.string.terminal_command_status_ready)
                        TerminalCommandState.CONNECTING -> stringResource(R.string.terminal_command_status_connecting)
                        TerminalCommandState.RUNNING -> stringResource(R.string.terminal_command_status_running)
                        TerminalCommandState.FINISHED -> stringResource(R.string.terminal_command_status_finished)
                    }, modifier = Modifier.padding(12.dp), color = Color.White)
                    if (state == TerminalCommandState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                    RemoteTerminalKeyBar(enabled = state == TerminalCommandState.READY || state == TerminalCommandState.RUNNING, view = view)
                }
            }
        }
    }
}
