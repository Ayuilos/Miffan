package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.previewText
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.thread.*
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.*
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Keyboard
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Mouse01
import me.rerere.workspace.screen.RfbKeys
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun PartnerScreenPage(assistantId: Uuid) {
    val computer by rememberPartnerComputer(assistantId)
    val nav = LocalNavController.current
    val current = computer
    if (current?.showsEntry != true) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.im_computer_screen)) },
            navigationIcon = { IconButton(onClick = nav::popBackStack) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) } }) }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.im_computer_setup_needed))
                Button(onClick = { nav.navigate(Screen.ComputerSetup(assistantId.toString())) }) { Text(stringResource(R.string.im_computer_connect)) }
            }
        }
        return
    }
    key(current.workspaceId) {
        val vm: RemoteScreenVM = koinViewModel(key = current.workspaceId, parameters = { parametersOf(RemoteScreenArgs(current.workspaceId)) })
        PartnerScreenContent(assistantId, current.name, vm)
    }
}

@Composable
private fun PartnerScreenContent(assistantId: Uuid, name: String, vm: RemoteScreenVM) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val thread: AgentThreadVM = koinViewModel(key = "thread:$assistantId", parameters = { parametersOf(assistantId) })
    val timeline by thread.timelineState.collectAsStateWithLifecycle()
    val generating by thread.generatingSegmentIds.collectAsStateWithLifecycle()
    val errors by thread.errors.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    val state by vm.state.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val bytes by vm.bytesReceived.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    var trackpad by rememberSaveable(name) { mutableStateOf(false) }
    var keyboard by rememberSaveable(name) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf("") }
    var meteredShown by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    val connected = state is RemoteScreenUiState.Connected
    RemoteScreenVisibility(vm)
    LifecycleResumeEffect(thread) {
        thread.setVisible(true)
        onPauseOrDispose { thread.setVisible(false) }
    }
    fun leave() { vm.handBackToPartner(); nav.popBackStack() }
    BackHandler { if (keyboard) keyboard = false else leave() }
    LaunchedEffect(state) { if (!connected) keyboard = false }
    LaunchedEffect(vm) {
        if (vm.metered && !meteredShown) {
            meteredShown = true
            launch { snackbar.showSnackbar(resources.getString(R.string.workspace_screen_metered)) }
        }
        vm.notices.collect { notice ->
            val text = when (notice) {
                RemoteScreenNotice.TextNotTypable -> resources.getString(R.string.workspace_screen_text_unsupported)
                is RemoteScreenNotice.ClipboardFailed -> resources.getString(R.string.workspace_screen_paste_failed)
                is RemoteScreenNotice.RemoteClipboard -> {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, notice.text))
                    resources.getString(R.string.workspace_screen_clipboard_copied)
                }
            }
            launch { snackbar.showSnackbar(text) }
        }
    }
    val replies = timeline.items.filterIsInstance<TimelineItem.Message>().filter { it.message.role == MessageRole.ASSISTANT }
    val latest = replies.lastOrNull { it.streaming } ?: replies.lastOrNull()
    val pending = replies.flatMap { item -> item.message.parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isComputerTool() && it.approvalState is ToolApprovalState.Pending }.map { item to it } }
    fun openChat(item: TimelineItem.Message?) {
        val id = assistantId.toString()
        // Remove existing variants of this thread, then return with the requested message focus.
        vm.handBackToPartner()
        nav.removeAll(Screen.Home) { it is Screen.Thread && it.assistantId == id || it == Screen.PartnerScreen(id) }
        nav.navigate(Screen.Thread(id, focusMessageId = item?.message?.id?.toString()))
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.im_computer_connection_usage,
                    stringResource(when (state) {
                        is RemoteScreenUiState.Connected -> R.string.im_computer_connected
                        RemoteScreenUiState.Connecting -> R.string.im_computer_connecting
                        else -> R.string.im_computer_disconnected
                    }), bytes.fileSizeToString()), style = MaterialTheme.typography.bodySmall)
            }
        }, navigationIcon = { IconButton(onClick = ::leave) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) } }, actions = {
            IconButton(enabled = connected, onClick = { trackpad = !trackpad }) {
                Icon(if (trackpad) HugeIcons.Mouse01 else HugeIcons.Computer,
                    stringResource(if (trackpad) R.string.workspace_screen_mode_trackpad else R.string.workspace_screen_mode_direct))
            }
            IconButton(enabled = connected, onClick = { keyboard = !keyboard }) { Icon(HugeIcons.Keyboard, stringResource(R.string.workspace_screen_keyboard)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, stringResource(R.string.workspace_screen_actions)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.workspace_screen_reconnect)) }, onClick = { menu = false; vm.reconnect() })
                    listOf("Esc" to RfbKeys.ESCAPE, "Tab" to RfbKeys.TAB, "Enter" to RfbKeys.RETURN).forEach { (label, code) ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.im_computer_send_key, label)) }, enabled = connected,
                            onClick = { menu = false; vm.key(code) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.im_computer_recheck_or_change)) },
                        onClick = { menu = false; vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(assistantId.toString())) })
                }
            }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
          val conversationHeight = (maxHeight * .35f).coerceAtMost(220.dp)
          Column(Modifier.fillMaxSize()) {
            RemoteScreenControllerBanner(controller, vm::handBackToPartner)
            RemoteScreenViewport(vm, trackpad, Modifier.weight(1f).fillMaxWidth()) { failed ->
                RemoteScreenFailureContent(failed, true, vm::reconnect,
                    onSettings = { vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(assistantId.toString())) },
                    failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let { computerErrorMessage(resources, it) }
                        ?: stringResource(R.string.im_computer_disconnected),
                    settingsText = stringResource(R.string.im_computer_connect))
            }
            if (keyboard && connected) RemoteScreenKeyboard(vm, platform == RemoteScreenPlatform.MACOS)
            Column(Modifier.fillMaxWidth().heightIn(max = conversationHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(onClick = { openChat(latest) }, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    val action = latest?.takeIf { it.streaming }?.message?.parts?.let(::latestComputerAction)
                    val preview = when {
                        action != null -> computerActionText(action)
                        latest?.streaming == true || generating.isNotEmpty() -> stringResource(R.string.im_thread_tools_working)
                        latest != null -> latest.message.previewText()
                        else -> stringResource(R.string.im_computer_chat_help)
                    }
                    Text(preview, Modifier.fillMaxWidth().padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                pending.forEach { (item, tool) ->
                    ThreadComputerApprovalCard(tool, computerTargetName(tool, computer, name), canOpenComputer(tool, computer),
                        onScreen = { keyboard = false },
                        onApproval = { id, approved ->
                            if (approved) vm.handBackToPartner()
                            thread.answerToolApproval(item, id, approved)
                        }, compact = true)
                }
                errors.lastOrNull()?.let { error ->
                    ThreadErrorBubble(error, error.conversationId != null,
                        onRetry = { error.conversationId?.let(thread::retry); thread.dismissError(error) },
                        onDismiss = { thread.dismissError(error) })
                }
            }
            ThreadComposer(input, { input = it }, null, timeline.loaded, generating.isNotEmpty(),
                onSend = thread::send, onStop = thread::stop,
                onError = { scope.launch { snackbar.showSnackbar(it) } }, onVoiceUnavailable = {}, hazeState = haze,
                compact = true)
          }
        }
    }
}
