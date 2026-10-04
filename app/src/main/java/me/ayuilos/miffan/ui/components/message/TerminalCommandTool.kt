package me.ayuilos.miffan.ui.components.message

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
            tool.isExecuted -> "终端执行结果"
            tool.approvalState is ToolApprovalState.Denied -> "已取消终端执行"
            tool.approvalState is ToolApprovalState.Answered -> "执行结果已返回，AI 正在处理"
            !pending -> "正在准备终端命令"
            state == TerminalCommandState.RUNNING -> "终端执行中"
            state == TerminalCommandState.CONNECTING -> "正在连接终端"
            state == TerminalCommandState.FINISHED && session != null -> "正在返回执行结果"
            else -> "等待你在终端执行"
        }, style = MaterialTheme.typography.titleSmall) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(arg("reason"), style = MaterialTheme.typography.bodyMedium)
                Text("${target?.remoteHostLabel.orEmpty()} · ${target?.remoteRoot.orEmpty()}\n工作目录：${cwd.ifBlank { "沿用会话当前目录" }}",
                    style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(command, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .verticalScroll(rememberScrollState()).padding(12.dp))
                }
                if (pending) {
                    if (session == null) Text("命令缺少当前聊天的会话信息，请取消后让 AI 重新提交。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showTerminal = true }, enabled = session != null && state != TerminalCommandState.FINISHED) {
                            Text(if (state == TerminalCommandState.READY) "打开终端" else "查看终端")
                        }
                        TextButton(onClick = {
                            if (session != null) session.cancel()
                            else onToolApproval?.invoke(tool.toolCallId, false, "用户取消终端执行")
                        }, enabled = onToolApproval != null || session != null) { Text("取消执行") }
                    }
                    Text("按回车开始；结束后自动返回聊天，终端会话保留供 AI 继续使用。", style = MaterialTheme.typography.bodySmall)
                }
                if (tool.isExecuted) {
                    val raw = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val result = remember(raw) { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
                    val sessionId = result?.get("sessionId")?.jsonPrimitive?.contentOrNull
                    val active = shellStates[conversationId]?.takeIf { it.sessionId == sessionId }
                    if (sessionId != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (active != null) "会话保持连接" else "会话已结束",
                                style = MaterialTheme.typography.labelSmall)
                            if (active != null && conversationId != null) TextButton(onClick = {
                                uiScope.launch { repository.closeConversationTerminal(conversationId, active.sessionId) }
                            }) { Text("结束会话") }
                        }
                    }
                    val status = result?.get("status")?.jsonPrimitive?.contentOrNull
                    Text(when (status) {
                        "completed" -> "执行结束 · 退出码 ${result["exitCode"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
                        "cancelled" -> "已取消，命令未执行"
                        "interrupted" -> "执行中断，远程结果未知"
                        else -> "执行结果"
                    }, style = MaterialTheme.typography.labelMedium)
                    val output = result?.get("output")?.jsonPrimitive?.contentOrNull
                    val error = result?.get("error")?.jsonPrimitive?.contentOrNull
                    val display = listOfNotNull(output?.takeIf { it.isNotBlank() }, error).joinToString("\n")
                        .ifBlank { if (result == null) raw else "无终端输出" }
                    SelectionContainer {
                        Text(display, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    if (result?.get("truncated")?.jsonPrimitive?.contentOrNull == "true") {
                        Text("输出较长，已截取前 64 KiB。", style = MaterialTheme.typography.labelSmall)
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
                    TextButton(onClick = onBack) { Text("返回聊天") }
                }, actions = { TextButton(onClick = session::cancel) { Text("结束") } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().background(Color.Black)) {
                    Text(when (state) {
                        TerminalCommandState.READY -> "命令已带入，按回车执行"
                        TerminalCommandState.CONNECTING -> "正在连接，请稍候"
                        TerminalCommandState.RUNNING -> "执行中 · 密码只在终端提示后输入"
                        TerminalCommandState.FINISHED -> "执行结束"
                    }, modifier = Modifier.padding(12.dp), color = Color.White)
                    if (state == TerminalCommandState.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                    RemoteTerminalKeyBar(enabled = state == TerminalCommandState.READY || state == TerminalCommandState.RUNNING, view = view)
                }
            }
        }
    }
}
