package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import me.ayuilos.miffan.R
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

/** Null while idle; otherwise follows the newest streaming reply, or thinking before its first token. */
internal fun threadHeaderStatus(timeline: List<TimelineItem>, generatingSegmentIds: Set<Uuid>): ThreadHeaderStatus? {
    if (generatingSegmentIds.isEmpty()) return null
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
