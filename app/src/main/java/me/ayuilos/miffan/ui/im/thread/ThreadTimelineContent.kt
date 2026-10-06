package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.ayuilos.miffan.data.thread.previewText
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.ui.context.LocalTTSState
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.components.ui.UIAvatar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.ui.components.message.groupMessageParts
import me.ayuilos.miffan.ui.components.message.MessagePartBlock
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.service.ChatError
import me.ayuilos.miffan.service.ChatErrorSolution
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.components.richtext.ZoomableAsyncImage
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
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
internal fun ThreadAvatar(assistant: Assistant?, phase: AssistantGenerationPhase = AssistantGenerationPhase.None, error: Boolean = false) {
    Box(Modifier.size(36.dp)) {
        AssistantAvatar(name = threadAssistantName(assistant), value = assistant?.avatar ?: Avatar.Miffan(), modifier = Modifier.fillMaxSize(),
            loading = phase != AssistantGenerationPhase.None, semanticState = if (error) MiffanMascotState.Error else MiffanMascotState.Idle, generationPhase = phase)
        if (error) Badge(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp)) { Text("!") }
    }
}

@Composable
internal fun ThreadMessageBubble(
    item: TimelineItem.Message,
    assistant: Assistant?,
    highlighted: Boolean = false,
    onReply: () -> Unit = {},
    onFilter: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onQuote: (MessageRef) -> Unit = {},
    onToolApproval: (String, Boolean) -> Unit = { _, _ -> },
    onToolAnswer: (String, String) -> Unit = { _, _ -> },
) {
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val latestReply by rememberUpdatedState(onReply)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tts = LocalTTSState.current
    val speaking by tts.isSpeaking.collectAsState()
    val canSpeak by tts.isAvailable.collectAsState()
    val settings = LocalSettings.current

    val user = item.message.role == MessageRole.USER
    val phase = when {
        !item.streaming || user -> AssistantGenerationPhase.None
        item.message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null } -> AssistantGenerationPhase.Reasoning
        else -> AssistantGenerationPhase.Responding
    }
    Row(Modifier.fillMaxWidth().padding(top = if (item.groupedWithPrevious) 4.dp else 16.dp)
        .pointerInput(item.key) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    drag = (drag + amount).coerceIn(-threshold * 1.5f, 0f)
                },
                onDragEnd = {
                    if (drag <= -threshold) {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        latestReply()
                    }
                    drag = 0f
                },
                onDragCancel = { drag = 0f },
            )
        }.offset { IntOffset(drag.roundToInt(), 0) },
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) {
            if (!item.groupedWithPrevious) ThreadAvatar(assistant, phase) else Spacer(Modifier.size(36.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.widthIn(max = 300.dp).weight(1f, fill = false)) {
            Surface(shape = RoundedCornerShape(20.dp),
                border = if (highlighted || menu) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                })) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.quote?.let { quote ->
                        val color = threadTopicColor(quote.topicIndex)
                        Surface(onClick = { onQuote(quote.ref) }, color = color.copy(alpha = .12f), contentColor = color, shape = RoundedCornerShape(10.dp)) {
                            Text("↩ ${quote.preview}", Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item.message.parts.groupMessageParts().forEach { block ->
                        if (block is MessagePartBlock.ThinkingBlock) {
                            ThreadToolStatus(block.steps, assistant, item.streaming, onToolApproval, onToolAnswer)
                        } else if (block is MessagePartBlock.ContentBlock) when (val part = block.part) {
                            is UIMessagePart.Text -> MarkdownBlock(part.text, style = MaterialTheme.typography.bodyLarge)
                            is UIMessagePart.Image -> ZoomableAsyncImage(part.url, stringResource(R.string.im_thread_photo), Modifier.heightIn(max = 200.dp))
                            is UIMessagePart.Document -> Text("▤ ${part.fileName}")
                            else -> Unit
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false; more = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.im_thread_reply)) }, onClick = { menu = false; onReply() })
                DropdownMenuItem(text = { Text(stringResource(R.string.im_thread_copy)) }, onClick = {
                    menu = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", item.message.toText()))) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.im_thread_filter)) }, onClick = { menu = false; onFilter() })
                if (item.canRegenerate) DropdownMenuItem(text = { Text(stringResource(R.string.im_thread_regenerate)) }, onClick = { menu = false; onRegenerate() })
                if (canSpeak && !user && item.message.previewText().isNotBlank()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.more_options)) }, onClick = { more = !more })
                    if (more) DropdownMenuItem(text = { Text(stringResource(R.string.tts)) }, onClick = {
                        menu = false; more = false
                        if (speaking) tts.stop() else tts.speak(item.message.previewText())
                    })
                }
            }
        }
        if (user) {
            Spacer(Modifier.width(8.dp))
            if (!item.groupedWithPrevious) UIAvatar(name = settings.displaySetting.userNickname, value = settings.displaySetting.userAvatar, modifier = Modifier.size(36.dp))
            else Spacer(Modifier.size(36.dp))
        }
    }
}

@Composable
internal fun ThreadTyping(assistant: Assistant?) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ThreadAvatar(assistant, AssistantGenerationPhase.Waiting)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Text(stringResource(R.string.im_thread_typing), Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ThreadErrorBubble(error: ChatError, assistant: Assistant?, canRetry: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
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
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThreadAvatar(assistant, error = true)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(text))
                Row {
                    if (canRetry && !toolsOff) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.im_thread_retry)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.im_thread_dismiss)) }
                }
            }
        }
    }
}
