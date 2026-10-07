package me.ayuilos.miffan.ui.im.thread

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
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
    modifier: Modifier = Modifier.size(36.dp)) {
    Box(modifier) {
        AssistantAvatar(name = threadAssistantName(assistant), value = assistant?.avatar ?: Avatar.Miffan(), modifier = Modifier.fillMaxSize(),
            loading = phase != AssistantGenerationPhase.None, semanticState = if (error) MiffanMascotState.Error else MiffanMascotState.Idle, generationPhase = phase)
        if (error) Badge(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp)) { Text("!") }
    }
}

/** Bubbles leave a gutter on the opposite side, like an IM, but stay readable on wide screens. */
@Composable
private fun threadBubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * .86f).coerceAtMost(640f).dp

@Composable
internal fun ThreadMessageBubble(
    item: TimelineItem.Message,
    highlighted: Boolean = false,
    onReply: () -> Unit = {},
    onFilter: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onQuote: (MessageRef) -> Unit = {},
    onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
    onToolAnswer: (String, String) -> Unit = { _, _ -> },
    computer: PartnerComputer? = null,
    onComputerScreen: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tts = LocalTTSState.current
    val speaking by tts.isSpeaking.collectAsState()
    val canSpeak by tts.isAvailable.collectAsState()

    val user = item.message.role == MessageRole.USER
    val parts = item.message.parts
    val evidence = remember(parts) { threadComputerEvidence(parts) }
    val liveComputer = item.streaming && latestComputerAction(parts)?.approvalState !is me.rerere.ai.ui.ToolApprovalState.Pending &&
        latestComputerAction(parts) != null
    val status = if (liveComputer) null else threadLiveStatus(parts, item.streaming)
    val computerName = evidence?.let { computerTargetName(it.latest, computer, stringResource(R.string.im_computer_computer)) }
    val computerAvailable = evidence?.let { canOpenComputer(it.latest, computer) } == true
    val visible = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image, is UIMessagePart.Document -> true
            is UIMessagePart.Tool -> part.isThreadPrompt()
            else -> false
        }
    }
    // A finished reply made only of process (thinking, tool calls) has nothing to show in easy mode.
    if (!visible && status == null && evidence == null && item.quote == null) return
    Row(Modifier.fillMaxWidth().padding(top = if (item.groupedWithPrevious) 4.dp else 16.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(Modifier.widthIn(max = threadBubbleMaxWidth())) {
            Surface(shape = RoundedCornerShape(20.dp),
                border = if (highlighted || menu) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                })) {
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
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> if (part.text.isNotBlank()) MarkdownBlock(part.text, style = MaterialTheme.typography.bodyLarge)
                            is UIMessagePart.Image -> ZoomableAsyncImage(part.url, stringResource(R.string.im_thread_photo), Modifier.heightIn(max = 200.dp))
                            is UIMessagePart.Document -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(HugeIcons.File01, null, Modifier.size(18.dp))
                                Text(part.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            is UIMessagePart.Tool -> if (part.isThreadPrompt()) {
                                if (part.isComputerTool()) ThreadComputerApprovalCard(part,
                                    computerTargetName(part, computer, stringResource(R.string.im_computer_computer)),
                                    canOpenComputer(part, computer), onComputerScreen, onToolApproval)
                                else ThreadToolPrompt(part, onToolApproval, onToolAnswer)
                            }
                            else -> Unit
                        }
                    }
                    if (evidence != null && computerName != null) {
                        if (liveComputer) ThreadComputerLiveCard(evidence, computerName, computerAvailable, onComputerScreen)
                        // A reply paused on an approval is not finished; its summary comes after the user answers.
                        else if (!item.streaming && evidence.tools.none { it.approvalState is me.rerere.ai.ui.ToolApprovalState.Pending })
                            ThreadComputerSummary(evidence, computerName, computerAvailable, onComputerScreen)
                    }
                    status?.let { ThreadLiveStatus(it) }
                }
            }
            val text = item.message.previewText()
            ThreadActionMenu(expanded = menu, onDismiss = { menu = false }) {
                ThreadAction(HugeIcons.Reply, stringResource(R.string.im_thread_reply)) { menu = false; onReply() }
                if (text.isNotBlank()) ThreadAction(HugeIcons.Copy01, stringResource(R.string.im_thread_copy)) {
                    menu = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", item.message.toText()))) }
                }
                if (item.canRegenerate) ThreadAction(HugeIcons.Refresh01, stringResource(R.string.im_thread_regenerate)) { menu = false; onRegenerate() }
                if (canSpeak && !user && text.isNotBlank()) {
                    ThreadAction(if (speaking) HugeIcons.VolumeOff else HugeIcons.VolumeHigh,
                        stringResource(if (speaking) R.string.im_thread_stop_reading else R.string.im_thread_read_aloud)) {
                        menu = false
                        if (speaking) tts.stop() else tts.speak(text)
                    }
                }
                ThreadAction(HugeIcons.Filter, stringResource(R.string.im_thread_filter)) { menu = false; onFilter() }
            }
        }
    }
}

/** The single entry point for acting on a message: long press opens the same kind of sheet as professional mode. */
@Composable
internal fun ThreadActionMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ThreadAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.padding(4.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ThreadTyping() {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { ThreadLiveStatus(R.string.im_thread_status_thinking) }
        }
    }
}

@Composable
internal fun ThreadErrorBubble(error: ChatError, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val detail = error.error.message.orEmpty().lowercase(Locale.ROOT)
    // The reply still arrives without tools, so this is a notice rather than a failed reply.
    val toolsOff = error.solution == ChatErrorSolution.EnableModelTools
    val text = when {
        toolsOff -> R.string.im_thread_error_tools_off
        listOf("401", "403", "api key", "unauthorized", "authentication").any { it in detail } -> R.string.im_thread_error_connection
        listOf("429", "rate limit", "quota").any { it in detail } -> R.string.im_thread_error_busy
        error.error is java.io.IOException -> R.string.im_thread_error_network
        else -> R.string.im_thread_error_reply
    }
    val retry = canRetry && !toolsOff
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.widthIn(max = threadBubbleMaxWidth())) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = if (menu) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                })) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(HugeIcons.Alert01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(text), Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
                    // Retry is the one inline action: a failed reply is where people look for it.
                    if (retry) IconButton(onClick = onRetry) { Icon(HugeIcons.Refresh01, stringResource(R.string.im_thread_retry)) }
                    else Spacer(Modifier.width(10.dp))
                }
            }
            ThreadActionMenu(expanded = menu, onDismiss = { menu = false }) {
                if (retry) ThreadAction(HugeIcons.Refresh01, stringResource(R.string.im_thread_retry)) { menu = false; onRetry() }
                ThreadAction(HugeIcons.Cancel01, stringResource(R.string.im_thread_dismiss)) { menu = false; onDismiss() }
            }
        }
    }
}
