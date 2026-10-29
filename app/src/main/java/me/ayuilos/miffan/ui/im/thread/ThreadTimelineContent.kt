package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.ui.components.ui.assistantGenerationPhase
import me.ayuilos.miffan.ui.components.richtext.workspaceImageContext
import me.ayuilos.miffan.ui.components.richtext.withWorkspaceFiles
import me.ayuilos.miffan.data.ai.tools.workspaceArtifacts
import me.ayuilos.miffan.ui.components.message.EditedFilesList
import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import me.ayuilos.miffan.ui.im.RunningLight
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import kotlin.uuid.Uuid
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImPressMenu
import me.ayuilos.miffan.ui.im.ImPressMenuItem
import me.ayuilos.miffan.ui.im.imLongPress
import me.ayuilos.miffan.ui.im.rememberPressMenu
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.previewText
import me.ayuilos.miffan.service.ChatError
import me.ayuilos.miffan.service.ChatErrorSolution
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.components.richtext.ZoomableAsyncImage
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.context.LocalTTSState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Star
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Reply
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.VolumeOff
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun threadTopicColor(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return listOf(scheme.primary, scheme.tertiary, scheme.secondary)[Math.floorMod(index, 3)]
}

@Composable
internal fun ThreadDateLabel(at: Instant) {
    val locale = LocalConfiguration.current.locales[0]
    val date = at.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = date.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val text = when (date.toLocalDate()) {
        today -> stringResource(R.string.im_thread_today, time)
        today.minusDays(1) -> stringResource(R.string.im_thread_yesterday, time)
        else -> if (date.year == today.year) {
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
            date.format(DateTimeFormatter.ofPattern(pattern, locale)) + " " + time
        } else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ThreadAvatar(assistant: Assistant?, phase: AssistantGenerationPhase = AssistantGenerationPhase.None, error: Boolean = false,
    modifier: Modifier = Modifier.size(36.dp), running: Boolean = false, runningRing: Color = MaterialTheme.colorScheme.surface,
    waiting: Boolean = false) {
    BoxWithConstraints(modifier) {
        AssistantAvatar(name = threadAssistantName(assistant), value = assistant?.avatar ?: Avatar.Miffan(), modifier = Modifier.fillMaxSize(),
            loading = phase != AssistantGenerationPhase.None, semanticState = if (error) MiffanMascotState.Error else MiffanMascotState.Idle, generationPhase = phase)
        if (error) Badge(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp)) { Text("!") }
        RunningLight(running, maxWidth, runningRing, waiting)
    }
}

/** Bubbles leave a gutter on the opposite side, like an IM, but stay readable on wide screens. */
@Composable
internal fun threadBubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * .86f).coerceAtMost(640f).dp

@Composable
internal fun ThreadMessageBubble(
    item: TimelineItem.Message,
    highlighted: Boolean = false,
    onReply: () -> Unit = {},
    onFilter: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    favorited: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onQuote: (MessageRef) -> Unit = {},
    onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
    onToolAnswer: (String, String) -> Unit = { _, _ -> },
    computer: PartnerComputer? = null,
    onComputerScreen: () -> Unit = {},
    assistant: Assistant? = null,
    connection: ThreadConnection = ThreadConnection(),
) {
    val menu = rememberPressMenu()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val tts = LocalTTSState.current
    val speaking by tts.isSpeaking.collectAsState()
    val canSpeak by tts.isAvailable.collectAsState()

    val user = item.message.role == MessageRole.USER
    val partners = LocalSettings.current.assistants
    val parts = item.message.parts
    val liveComputer = computerWorkIsLive(parts, item.streaming)
    val steps = remember(parts) { if (user) emptyList() else threadSteps(parts) }
    val recordSteps = steps.worthRecording()
    val blocks = remember(parts, recordSteps, item.streaming) { threadReplyBlocks(parts, recordSteps, item.streaming) }
    val liveStatus = threadLiveStatus(parts, item.streaming, item.segmentId in connection.connected, connection.online)
    // The record and the live computer card already say what is happening.
    val status = if (liveComputer || recordSteps && item.streaming) null else liveStatus
    // Files the partner published (screenshots, reports) are part of its answer, not process.
    val imageContext = remember(item.message.id, parts, computer?.workspaceId) {
        item.message.takeIf { !user }?.workspaceImageContext(null, computer?.workspaceId)
    }
    val handed = remember(parts) { if (user) emptyList() else handedFiles(parts) }
    val published = remember(parts) { !user && parts.any { it is UIMessagePart.Tool && it.workspaceArtifacts().isNotEmpty() } }
    // Edited files are listed once the reply finishes, so they do not count as content while it streams.
    val visible = (published && !item.streaming) || parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image, is UIMessagePart.Document -> true
            is UIMessagePart.Tool -> part.isThreadPrompt() || part.showsInThread(item.streaming) || part.addedCalendarEvent() != null
            else -> false
        }
    }
    // A reply made only of light process (thinking, a lookup or two) has nothing to show in easy mode;
    // its progress gets its own line below. Steps that changed something, or many steps, keep a record.
    val showBubble = visible || blocks.any { it !is ThreadReplyBlock.Content } || item.quote != null
    if (!showBubble && status == null) return
    if (showBubble) Row(Modifier.fillMaxWidth().padding(top = if (item.groupedWithPrevious) 4.dp else 16.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = threadBubbleMaxWidth())) {
            Surface(shape = RoundedCornerShape(20.dp),
                border = if (highlighted || menu.at != null) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.imLongPress(menu::open)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.quote?.let { quote ->
                        val color = threadTopicColor(quote.topicIndex)
                        Surface(onClick = { onQuote(quote.ref) }, color = color.copy(alpha = .12f), contentColor = color, shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(HugeIcons.Reply, null, Modifier.size(16.dp))
                                Text(quote.preview, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val batch = remember(parts) { parts.batchApprovals() }
                    CompositionLocalProvider(LocalToolCardSiblings provides parts) { blocks.forEachIndexed { index, block ->
                        // Only the step being taken right now is live; text after a run of steps means it is done.
                        val current = item.streaming && index == blocks.lastIndex
                        when (block) {
                            is ThreadReplyBlock.Steps -> key(block.tools.first().toolCallId) {
                                ThreadStepRecord(block.steps, live = current, liveStatus = liveStatus)
                            }
                            is ThreadReplyBlock.Computer -> key(block.tools.first().toolCallId) {
                                ThreadComputerBlock(block.tools, live = liveComputer, computer, onComputerScreen, onToolApproval, threadAssistantName(assistant))
                            }
                            is ThreadReplyBlock.Content -> Unit
                        }
                        val part = (block as? ThreadReplyBlock.Content)?.part
                        // Several plain requests waiting together: one "allow all" right above the first of them.
                        if (batch.size > 1 && part === batch.first())
                            ThreadBatchApprovalHeader(batch.size) { batch.forEach { onToolApproval(it.toolCallId, true) } }
                        when (part) {
                            is UIMessagePart.Text -> if (part.text.isBlank()) Unit
                            // The owner's own words with @partners in them read as typed, the mentions colored as in the input.
                            else if (user && hasPartnerMention(part.text, partners)) Text(
                                mentionText(part.text, partners, SpanStyle(fontWeight = FontWeight.SemiBold)),
                                style = MaterialTheme.typography.bodyLarge, inlineContent = mentionAvatars(partners))
                            else MarkdownBlock(part.text.withWorkspaceFiles(imageContext),
                                style = MaterialTheme.typography.bodyLarge)
                            is UIMessagePart.Image -> ZoomableAsyncImage(part.url, stringResource(R.string.im_thread_photo), Modifier.heightIn(max = 200.dp))
                            is UIMessagePart.Document -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(HugeIcons.File01, null, Modifier.size(18.dp))
                                Text(part.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            is UIMessagePart.Tool -> if (part.isThreadPrompt()) {
                                if (part.isPartnerMessage()) ThreadPartnerMessage(part, assistant, onToolApproval)
                                else if (part.isComputerTool() && part.approvalState is me.rerere.ai.ui.ToolApprovalState.Pending) ThreadComputerApprovalCard(part,
                                    computerTargetName(part, computer, stringResource(R.string.im_computer_computer),
                                        stringResource(R.string.im_computer_detail_user_space)),
                                    canOpenComputer(part, computer), onComputerScreen, onToolApproval)
                                else ThreadToolPrompt(part, onToolApproval, onToolAnswer, assistant, item.segmentId.toString())
                            } else if (part.isImageGeneration()) ThreadGeneratedImages(part, item.streaming)
                            // Added without asking (the owner allowed it for good): the event still shows.
                            else part.addedCalendarEvent()?.let { ThreadCalendarEvent(it) }
                            else -> Unit
                        }
                    } }
                    if (published && !item.streaming) EditedFilesList(parts, assistant = null, imageContext = imageContext)
                    handed.forEach { file -> key(file.toolCallId) { ThreadHandedFileCard(file, threadAssistantName(assistant)) } }
                }
            }
            val text = item.message.previewText()
            ImPressMenu(menu) {
                ImPressMenuItem(HugeIcons.Reply, stringResource(R.string.im_thread_reply)) { menu.close(); onReply() }
                if (text.isNotBlank()) ImPressMenuItem(HugeIcons.Copy01, stringResource(R.string.im_thread_copy)) {
                    menu.close()
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", item.message.toText()))) }
                }
                if (item.canRegenerate) ImPressMenuItem(HugeIcons.Refresh01, stringResource(R.string.im_thread_regenerate)) { menu.close(); onRegenerate() }
                if (onFavorite != null && !item.streaming) ImPressMenuItem(HugeIcons.Star, stringResource(if (favorited) R.string.im_thread_unfavorite else R.string.im_thread_favorite)) {
                    menu.close(); onFavorite()
                }
                if (canSpeak && !user && text.isNotBlank()) {
                    ImPressMenuItem(if (speaking) HugeIcons.VolumeOff else HugeIcons.VolumeHigh,
                        stringResource(if (speaking) R.string.im_thread_stop_reading else R.string.im_thread_read_aloud)) {
                        menu.close()
                        if (speaking) tts.stop() else tts.speak(text)
                    }
                }
                ImPressMenuItem(HugeIcons.Filter, stringResource(R.string.im_thread_filter)) { menu.close(); onFilter() }
            }
        }
    }
    status?.let { ThreadLiveStatusRow(assistant, assistantGenerationPhase(item.message, loading = true), it) }
}

@Composable
internal fun ThreadTyping(assistant: Assistant?, segmentIds: Set<Uuid>, connection: ThreadConnection) {
    val status = waitingStatus(segmentIds.any { it in connection.connected }, connection.online)
    ThreadLiveStatusRow(assistant, AssistantGenerationPhase.Waiting, status.label)
}

@Composable
internal fun ThreadErrorBubble(error: ChatError, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val menu = rememberPressMenu()
    val detail = error.error.message.orEmpty().lowercase(Locale.ROOT)
    // The reply still arrives without tools, so this is a notice rather than a failed reply.
    val toolsOff = error.solution == ChatErrorSolution.EnableModelTools
    val text = when {
        toolsOff -> R.string.im_thread_error_tools_off
        listOf("401", "403", "api key", "unauthorized", "authentication").any { it in detail } -> R.string.im_thread_error_connection
        // Checked before "busy": an empty balance also answers 429 ("insufficient_quota").
        listOf("402", "insufficient", "balance", "余额", "欠费").any { it in detail } -> R.string.im_thread_error_balance
        listOf("429", "rate limit", "quota").any { it in detail } -> R.string.im_thread_error_busy
        error.error is java.io.IOException -> R.string.im_thread_error_network
        else -> R.string.im_thread_error_reply
    }
    val retry = canRetry && !toolsOff
    // Problems only the service's settings can fix lead there.
    val nav = LocalNavController.current
    val toService = text == R.string.im_thread_error_connection || text == R.string.im_thread_error_balance
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.widthIn(max = threadBubbleMaxWidth())) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = if (menu.at != null) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.imLongPress(menu::open)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(HugeIcons.Alert01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(text), Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
                    if (toService) TextButton(onClick = { nav.navigate(Screen.ImAiService) }) {
                        Text(stringResource(R.string.im_thread_error_open_service))
                    }
                    // Retry is the one inline action: a failed reply is where people look for it.
                    if (retry) IconButton(onClick = onRetry) { Icon(HugeIcons.Refresh01, stringResource(R.string.im_thread_retry)) }
                    else Spacer(Modifier.width(10.dp))
                }
            }
            ImPressMenu(menu) {
                if (retry) ImPressMenuItem(HugeIcons.Refresh01, stringResource(R.string.im_thread_retry)) { menu.close(); onRetry() }
                ImPressMenuItem(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss)) { menu.close(); onDismiss() }
            }
        }
    }
}

/**
 * Plain allow-or-decline requests waiting together in one reply. Requests that need more than a yes
 * (questions, terminals, drawing setup, bug reports, computer steps, messages to partners) stay one by one.
 */
private fun List<UIMessagePart>.batchApprovals(): List<UIMessagePart.Tool> = filterIsInstance<UIMessagePart.Tool>().filter {
    it.approvalState is me.rerere.ai.ui.ToolApprovalState.Pending && it.toolName != ASK_USER &&
        it.toolName != me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME && !it.isImageGeneration() &&
        it.toolName != DRAFT_BUG_REPORT && !it.isComputerTool() && !it.isPartnerMessage()
}

@Composable
private fun ThreadBatchApprovalHeader(count: Int, onAllowAll: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.im_approval_batch, count, count), Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall)
        FilledTonalButton(onClick = { done = true; onAllowAll() }, enabled = !done) {
            Text(stringResource(R.string.im_approval_allow_all))
        }
    }
}
