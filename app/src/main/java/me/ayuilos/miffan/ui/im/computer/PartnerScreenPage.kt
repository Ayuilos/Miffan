package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.ayuilos.miffan.ui.components.ui.assistantGenerationPhase
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
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.thread.*
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val thread: AgentThreadVM = koinViewModel(key = "thread:$assistantId", parameters = { parametersOf(assistantId) })
    val timeline by thread.timelineState.collectAsStateWithLifecycle()
    val generating by thread.generatingSegmentIds.collectAsStateWithLifecycle()
    val errors by thread.errors.collectAsStateWithLifecycle()
    val assistant by thread.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    LifecycleResumeEffect(thread) {
        thread.setVisible(true)
        onPauseOrDispose { thread.setVisible(false) }
    }
    val replies = timeline.items.filterIsInstance<TimelineItem.Message>().filter { it.message.role == MessageRole.ASSISTANT }
    val latest = replies.lastOrNull { it.streaming } ?: replies.lastOrNull()
    val pending = replies.flatMap { item -> item.message.parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isComputerTool() && it.approvalState is ToolApprovalState.Pending }.map { item to it } }
    val partnerBusy = latest?.streaming == true && latest.message.parts.let(::latestComputerAction)
        ?.let { it.approvalState !is ToolApprovalState.Pending } == true
    fun openChat(item: TimelineItem.Message?) {
        val id = assistantId.toString()
        // Remove existing variants of this thread, then return with the requested message focus.
        vm.handBackToPartner()
        nav.removeAll(Screen.Home) { it is Screen.Thread && it.assistantId == id || it == Screen.PartnerScreen(id) }
        nav.navigate(Screen.Thread(id, focusMessageId = item?.message?.id?.toString()))
    }
    RemoteScreenScaffold(
        vm = vm,
        title = name,
        settingsLabel = stringResource(R.string.im_computer_recheck_or_change),
        onSettings = { vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(assistantId.toString())) },
        onBack = { vm.handBackToPartner(); nav.popBackStack() },
        failureText = { computerErrorMessage(resources, it) },
        partnerBusy = partnerBusy,
        snackbar = snackbar,
    ) {
        // The partner's side of the page: what it is doing, what it asks, and a way to talk to it.
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(onClick = { openChat(latest) }, shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                val action = latest?.takeIf { it.streaming }?.message?.parts?.let(::latestComputerAction)
                val working = latest?.streaming == true || generating.isNotEmpty()
                val preview = when {
                    action != null -> computerActionText(action)
                    working -> stringResource(R.string.im_thread_tools_working)
                    latest != null -> latest.message.previewText()
                    else -> stringResource(R.string.im_computer_chat_help)
                }
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThreadAvatar(assistant, assistantGenerationPhase(latest?.message, loading = working), modifier = Modifier.size(32.dp))
                    Text(preview, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (latest == null && !working) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
            pending.forEach { (item, tool) ->
                // Already on the screen; the card's own "view screen" link has nowhere to go.
                ThreadComputerApprovalCard(tool, computerTargetName(tool, computer, name), canOpen = false,
                    onScreen = {},
                    onApproval = { id, approved ->
                        // Allowing an action hands the desktop back to the partner.
                        if (approved) vm.handBackToPartner()
                        thread.answerToolApproval(item, id, approved, via = me.rerere.ai.ui.ToolDecisionVia.PARTNER_SCREEN)
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
