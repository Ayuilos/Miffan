package me.ayuilos.miffan.ui.im.computer

import org.koin.compose.koinInject
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import me.ayuilos.miffan.data.ai.computer.ComputerSnapshots
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.ui.components.message.ComputerToolStatus
import me.ayuilos.miffan.ui.components.message.computerToolStatus
import me.ayuilos.miffan.ui.components.message.tools.DefaultToolPreview
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CancelCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.PauseCircle
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
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
    if (current.space) {
        // Someone about to look at the Space should see their language, not boxes.
        val spaces: PartnerSpaces = koinInject()
        LaunchedEffect(current.workspaceId) { spaces.ensureFont(current.workspaceId) }
    }
    key(current.workspaceId) {
        val vm: RemoteScreenVM = koinViewModel(key = current.workspaceId, parameters = { parametersOf(RemoteScreenArgs(current.workspaceId)) })
        val partner = koinViewModel<AgentThreadVM>(key = "thread:$assistantId", parameters = { parametersOf(assistantId) })
            .assistant.collectAsStateWithLifecycle().value
        // Pages that show this computer at rest (its detail, the chat) pick up where the user left it.
        val snapshots: ComputerSnapshots = koinInject()
        DisposableEffect(vm) { onDispose { vm.bitmap.value?.let { snapshots.capture(current.workspaceId, it) } } }
        // A partner's own computer is named after the partner; the host's name would read as the host's screen.
        PartnerScreenContent(assistantId, if (current.space) stringResource(R.string.im_space_screen_title, threadAssistantName(partner)) else current.name, vm)
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
    val controller by vm.controller.collectAsStateWithLifecycle()
    val holder by vm.holder.collectAsStateWithLifecycle()
    var input by rememberSaveable(assistantId.toString()) { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    LifecycleResumeEffect(thread) {
        thread.setVisible(true)
        onPauseOrDispose { thread.setVisible(false) }
    }
    val partnerName = threadAssistantName(assistant)
    val replies = timeline.items.filterIsInstance<TimelineItem.Message>().filter { it.message.role == MessageRole.ASSISTANT }
    val latest = replies.lastOrNull { it.streaming } ?: replies.lastOrNull()
    // The feed follows the partner's current work, else the last reply that did something on a computer.
    val subject = latest?.takeIf { it.streaming || it.message.parts.any { part -> part is UIMessagePart.Tool && part.isComputerTool() } }
        ?: replies.lastOrNull { item -> item.message.parts.any { it is UIMessagePart.Tool && it.isComputerTool() } }
    // Everything the partner is waiting on, not only computer actions: commands and questions block it too.
    val pending = replies.flatMap { item -> item.message.parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.approvalState is ToolApprovalState.Pending }.map { item to it } }
    val pendingComputer = pending.firstOrNull { it.second.isComputerTool() }
    val userHolds = controller == me.ayuilos.miffan.data.ai.computer.RemoteController.USER && holder != null
    val current = subject?.takeIf { it.streaming }?.message?.parts?.filterIsInstance<UIMessagePart.Tool>()
        ?.firstOrNull { it.isComputerTool() && !it.isExecuted && it.approvalState !is ToolApprovalState.Denied }
    val point = rememberActionPoint(pendingComputer?.second, pendingComputer?.first?.message?.parts.orEmpty())
    fun openChat(item: TimelineItem.Message?) {
        val id = assistantId.toString()
        // Remove existing variants of this thread, then return with the requested message focus.
        vm.handBackToPartner()
        nav.removeAll(Screen.Home) { it is Screen.Thread && it.assistantId == id || it == Screen.PartnerScreen(id) }
        nav.navigate(Screen.Thread(id, focusMessageId = item?.message?.id?.toString()))
    }
    val captionText = current?.let { computerActionText(it) }
    RemoteScreenScaffold(
        vm = vm,
        title = name,
        settingsLabel = stringResource(R.string.im_computer_recheck_or_change),
        onSettings = { vm.handBackToPartner(); nav.navigate(Screen.ComputerSetup(assistantId.toString())) },
        onBack = { vm.handBackToPartner(); nav.popBackStack() },
        failureText = { computerErrorMessage(resources, it) },
        snackbar = snackbar,
        partnerLabel = partnerName,
        avatar = { modifier, active -> ThreadAvatar(assistant, modifier = modifier, running = active) },
        caption = captionText,
        marker = point?.takeIf { pendingComputer != null }?.let { (x, y) -> RemoteScreenMarker(x, y, stringResource(R.string.im_screen_marker_here)) },
        awaitingApproval = pendingComputer != null,
        framed = true,
    ) {
        Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val underway = subject?.streaming == true || pending.any { it.first == subject }
                    Text(stringResource(if (underway) R.string.im_screen_feed_working else R.string.im_screen_feed_done, partnerName)
                        .takeIf { subject != null }.orEmpty(),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { openChat(subject ?: latest) }) {
                        Text(stringResource(R.string.im_screen_feed_chat))
                        Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
                    }
                }
                if (subject == null) Text(stringResource(R.string.im_screen_feed_empty, partnerName),
                    Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else PartnerScreenFeed(subject, partnerName, paused = userHolds)
                pending.filterNot { it.second.isComputerTool() }.forEach { (item, tool) ->
                    ThreadToolPrompt(tool,
                        onApproval = { id, approved ->
                            thread.answerToolApproval(item, id, approved, via = me.rerere.ai.ui.ToolDecisionVia.PARTNER_SCREEN)
                        },
                        onAnswer = { id, answer -> thread.answerToolQuestion(item, id, answer) },
                        conversationId = item.segmentId.toString())
                }
                errors.lastOrNull()?.let { error ->
                    ThreadErrorBubble(error, error.conversationId != null,
                        onRetry = { error.conversationId?.let(thread::retry); thread.dismissError(error) },
                        onDismiss = { thread.dismissError(error) })
                }
            }
        }
        pendingComputer?.let { (item, tool) ->
            PartnerScreenApproval(tool, partnerName, marked = point != null, avatar = { ThreadAvatar(assistant, modifier = it) },
                onAnswer = { approved ->
                    // Allowing an action hands the desktop back to the partner.
                    if (approved) vm.handBackToPartner()
                    thread.answerToolApproval(item, tool.toolCallId, approved, via = me.rerere.ai.ui.ToolDecisionVia.PARTNER_SCREEN)
                })
        }
        ThreadComposer(input, { input = it }, null, timeline.loaded, generating.isNotEmpty(),
            onSend = thread::send, onStop = thread::stop,
            onError = { scope.launch { snackbar.showSnackbar(it) } }, onVoiceUnavailable = {}, hazeState = haze,
            compact = true)
    }
}

/** Observation calls (looking, listing, reading the guide) say little in a step list; the actions are the story. */
private val QUIET_COMPUTER_TOOLS = setOf("start", "get_desktop_state", "get_window_state", "list_windows", "list_apps", "read_guide",
    "get_screen_size", "get_cursor_position", "zoom", "verify_state", "clipboard_read", "screenshot")

/** What the partner said, the computer steps it took in order, and what it said after, for one reply. */
@Composable
private fun PartnerScreenFeed(reply: TimelineItem.Message, partnerName: String, paused: Boolean) {
    val parts = reply.message.parts
    val tools = parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isComputerTool() }
    // Calls run one after another; only the first one not done yet is under way, the rest wait their turn.
    val running = tools.firstOrNull { !it.isExecuted && it.approvalState !is ToolApprovalState.Denied }
    val steps = tools.filter { it.toolName.removePrefix(COMPUTER_TOOL_PREFIX) !in QUIET_COMPUTER_TOOLS || (it === running && reply.streaming) }
    val firstTool = parts.indexOfFirst { it is UIMessagePart.Tool }
    val lastTool = parts.indexOfLast { it is UIMessagePart.Tool }
    fun text(range: List<UIMessagePart>) = range.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim().takeIf { it.isNotBlank() }
    val opening = text(if (firstTool < 0) parts else parts.subList(0, firstTool))
    val closing = if (lastTool >= 0 && !reply.streaming) text(parts.subList(lastTool + 1, parts.size)) else null
    opening?.let { PartnerScreenBubble(it) }
    if (paused && reply.streaming) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(end = 8.dp)) {
        Text(stringResource(R.string.im_screen_paused_notice, partnerName), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    if (steps.isNotEmpty()) Column {
        steps.forEachIndexed { index, tool ->
            val action = computerActionText(tool)
            val status = if (tool.isExecuted) computerToolStatus(tool.output) else null
            val state = when {
                tool.approvalState is ToolApprovalState.Pending -> StepState.WAIT
                tool.approvalState is ToolApprovalState.Denied -> StepState.DENIED
                tool === running && reply.streaming -> if (paused) StepState.PAUSED else StepState.LIVE
                !tool.isExecuted -> StepState.QUEUED
                status == ComputerToolStatus.ERROR -> StepState.FAILED
                // Refused because the user held the screen: it never happened.
                status == ComputerToolStatus.REFUSED -> StepState.DENIED
                else -> StepState.DONE
            }
            PartnerScreenStep(when (state) {
                StepState.WAIT -> stringResource(R.string.im_screen_step_wait, action)
                StepState.DENIED -> stringResource(if (status == ComputerToolStatus.REFUSED) R.string.im_screen_step_held else R.string.im_screen_step_denied, action)
                StepState.FAILED -> stringResource(R.string.im_screen_step_failed, action)
                else -> action
            }, state, rail = index < steps.lastIndex)
        }
    }
    closing?.let { PartnerScreenBubble(it) }
}

private enum class StepState { DONE, LIVE, PAUSED, WAIT, DENIED, FAILED, QUEUED }

@Composable
private fun PartnerScreenBubble(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp),
        modifier = Modifier.padding(end = 8.dp)) {
        // The partner writes Markdown, as in the chat; the feed scrolls, so nothing is cut short.
        MarkdownBlock(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One line of the step timeline: an icon for where the step stands, joined to the next by a thin rail. */
@Composable
private fun PartnerScreenStep(text: String, state: StepState, rail: Boolean) {
    val quiet = state == StepState.DONE || state == StepState.DENIED || state == StepState.FAILED || state == StepState.QUEUED
    val railColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(22.dp).fillMaxHeight()) {
            if (rail) Box(Modifier.padding(top = 24.dp).align(Alignment.TopCenter).width(1.5.dp).fillMaxHeight().background(railColor))
            Box(Modifier.padding(top = 2.dp).size(18.dp).align(Alignment.TopCenter), contentAlignment = Alignment.Center) {
                when (state) {
                    StepState.LIVE -> CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.tertiary, strokeWidth = 2.dp,
                        trackColor = MaterialTheme.colorScheme.tertiaryContainer)
                    StepState.PAUSED -> StepIcon(HugeIcons.PauseCircle, MaterialTheme.colorScheme.primary)
                    StepState.WAIT -> StepIcon(HugeIcons.AlertCircle, MaterialTheme.colorScheme.tertiary)
                    StepState.DENIED, StepState.FAILED -> StepIcon(HugeIcons.CancelCircle, MaterialTheme.colorScheme.onSurfaceVariant)
                    StepState.DONE -> StepIcon(HugeIcons.CheckmarkCircle02, MaterialTheme.colorScheme.primary)
                    StepState.QUEUED -> Box(Modifier.size(14.dp).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
        }
        Text(text, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (quiet) null else FontWeight.Medium,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StepIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) =
    Icon(icon, null, Modifier.size(18.dp), tint = tint)

/** The partner asking to act on the screen: what it wants, where (circled on the picture when known), and yes or no. */
@Composable
private fun PartnerScreenApproval(tool: UIMessagePart.Tool, partnerName: String, marked: Boolean, avatar: @Composable (Modifier) -> Unit,
    onAnswer: (Boolean) -> Unit) {
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), shape = RoundedCornerShape(22.dp),
        shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { details = true }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                avatar(Modifier.size(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.im_screen_wants, partnerName, computerActionText(tool)), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (marked) R.string.im_screen_wants_marked else R.string.im_screen_wants_plain),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(HugeIcons.ArrowRight01, stringResource(R.string.im_computer_details), Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { answered = true; onAnswer(false) }, enabled = !answered, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text(stringResource(R.string.im_computer_decline))
                }
                Button(onClick = { answered = true; onAnswer(true) }, enabled = !answered, modifier = Modifier.weight(1f).height(44.dp)) {
                    Text(stringResource(R.string.im_computer_allow))
                }
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        DefaultToolPreview(ToolUIContext(tool, tool.inputAsJson(), null, false))
    }
}

/**
 * Where [tool] acts, as a share of the screen: its x/y are pixels of the last screenshot the partner took before it, so they
 * are scaled by that picture's size. Null for actions aimed at an element rather than a point, or without a screenshot.
 */
@Composable
private fun rememberActionPoint(tool: UIMessagePart.Tool?, parts: List<UIMessagePart>): Pair<Float, Float>? {
    val input = tool?.inputAsJson() as? JsonObject
    val x = (input?.get("x") as? JsonPrimitive)?.floatOrNull
    val y = (input?.get("y") as? JsonPrimitive)?.floatOrNull
    val index = parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == tool?.toolCallId }
    val screenshot = if (index < 0) null else parts.subList(0, index).filterIsInstance<UIMessagePart.Tool>()
        .flatMap { it.output }.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url
    val size by produceState<Pair<Int, Int>?>(null, screenshot) {
        value = screenshot?.let { url -> withContext(Dispatchers.IO) { imageSize(url) } }
    }
    val (width, height) = size ?: return null
    if (x == null || y == null || width <= 0 || height <= 0) return null
    return (x / width).coerceIn(0f, 1f) to (y / height).coerceIn(0f, 1f)
}

private fun imageSize(url: String): Pair<Int, Int>? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(requireNotNull(url.toUri().path), options)
    options.outWidth to options.outHeight
}.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

/** A reply that grows after the page opened (it was paused on a question, say) counts as new. */
private fun replyKey(item: TimelineItem.Message) = "${item.message.id}:${item.message.parts.size}"
