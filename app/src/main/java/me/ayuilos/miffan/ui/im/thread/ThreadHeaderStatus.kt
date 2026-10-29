package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

/** How long a request may stay unanswered before "connecting" admits the connection is slow. */
internal const val SLOW_CONNECTION_MILLIS = 15_000L

/**
 * Where pending replies stand on the way to the model: [connected] segments have been answered by the provider,
 * and [online] is whether the phone reaches the internet at all.
 */
internal data class ThreadConnection(val connected: Set<Uuid> = emptySet(), val online: Boolean = true)

/** The status of a reply that has not written anything yet. */
internal fun waitingStatus(connected: Boolean, online: Boolean): ThreadHeaderStatus = when {
    connected -> ThreadHeaderStatus.Thinking
    online -> ThreadHeaderStatus.Connecting
    else -> ThreadHeaderStatus.Offline
}

/** What the partner is doing right now, shown under its name in the thread header. */
internal enum class ThreadHeaderStatus(@StringRes val label: Int) {
    Offline(R.string.im_thread_status_offline),
    Connecting(R.string.im_thread_status_connecting),
    Thinking(R.string.im_thread_status_thinking),
    UsingTools(R.string.im_thread_status_tools),
    Typing(R.string.im_thread_typing),
}

/**
 * Null while idle; otherwise follows the newest streaming reply: connecting until the provider answers the request,
 * then thinking until its first token. A typing row also counts as connecting: a sent message is routed to a topic
 * before its generation starts.
 */
internal fun threadHeaderStatus(
    timeline: List<TimelineItem>,
    generatingSegmentIds: Set<Uuid>,
    connection: ThreadConnection,
): ThreadHeaderStatus? {
    if (generatingSegmentIds.isEmpty()) {
        return if (timeline.any { it is TimelineItem.Typing }) waitingStatus(connected = false, connection.online) else null
    }
    val reply = timeline.lastOrNull { it is TimelineItem.Message && it.streaming && it.message.role == MessageRole.ASSISTANT }
        as TimelineItem.Message?
        ?: return waitingStatus(generatingSegmentIds.any { it in connection.connected }, connection.online)
    for (part in reply.message.parts.asReversed()) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) return ThreadHeaderStatus.Typing
            is UIMessagePart.Reasoning -> return ThreadHeaderStatus.Thinking
            is UIMessagePart.Tool, is UIMessagePart.ServerTool -> return ThreadHeaderStatus.UsingTools
            is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video -> return ThreadHeaderStatus.Typing
            else -> Unit
        }
    }
    return waitingStatus(reply.segmentId in connection.connected, connection.online)
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
    if (status == ThreadHeaderStatus.Connecting) return stringResource(slowAwareLabel(status.label))
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

/** [label], or its slow variant once "connecting" has gone on for [SLOW_CONNECTION_MILLIS]. */
@Composable
@StringRes
internal fun slowAwareLabel(@StringRes label: Int): Int {
    if (label != R.string.im_thread_status_connecting) return label
    var slow by remember(label) { mutableStateOf(false) }
    LaunchedEffect(label) {
        delay(SLOW_CONNECTION_MILLIS)
        slow = true
    }
    return if (slow) R.string.im_thread_status_connecting_slow else label
}
