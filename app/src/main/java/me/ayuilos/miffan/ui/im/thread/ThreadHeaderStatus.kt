package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.im.ThinkTime
import me.ayuilos.miffan.data.thread.TimelineItem
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** What the partner is doing right now, shown under its name in the thread header. */
internal enum class ThreadHeaderStatus(@StringRes val label: Int) {
    Thinking(R.string.im_thread_status_thinking),
    UsingTools(R.string.im_thread_status_tools),
    Typing(R.string.im_thread_typing),
}

/**
 * Null while idle; otherwise follows the newest streaming reply, or thinking before its first token.
 * A typing row also counts: a sent message is routed to a topic before its generation starts.
 */
internal fun threadHeaderStatus(timeline: List<TimelineItem>, generatingSegmentIds: Set<Uuid>): ThreadHeaderStatus? {
    if (generatingSegmentIds.isEmpty()) return if (timeline.any { it is TimelineItem.Typing }) ThreadHeaderStatus.Thinking else null
    val reply = timeline.lastOrNull { it is TimelineItem.Message && it.streaming && it.message.role == MessageRole.ASSISTANT }
        as TimelineItem.Message? ?: return ThreadHeaderStatus.Thinking
    for (part in reply.message.parts.asReversed()) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) return ThreadHeaderStatus.Typing
            is UIMessagePart.Reasoning -> return ThreadHeaderStatus.Thinking
            is UIMessagePart.Tool, is UIMessagePart.ServerTool -> return ThreadHeaderStatus.UsingTools
            is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video -> return ThreadHeaderStatus.Typing
            else -> Unit
        }
    }
    return ThreadHeaderStatus.Thinking
}

/**
 * The header line. Long thinking ([ThinkTime.MORE] and up) counts the seconds since the partner got
 * busy at [since], so a slow reply reads as working rather than stuck.
 */
@Composable
internal fun threadStatusText(status: ThreadHeaderStatus, depth: ThinkTime?, since: Long?): String {
    val timed = when {
        status != ThreadHeaderStatus.Thinking || since == null -> null
        depth == ThinkTime.DEEP -> R.string.im_thread_status_thinking_deep
        depth == ThinkTime.MORE -> R.string.im_thread_status_thinking_more
        else -> null
    }
    if (timed == null || since == null) return stringResource(status.label)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return stringResource(timed, ((now - since) / 1_000).coerceAtLeast(0).toInt())
}
