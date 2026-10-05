package me.ayuilos.miffan.data.thread

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageRef
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

/** One row of an assistant's IM timeline, which merges every segment (conversation) by time. */
sealed interface TimelineItem {
    val key: String

    /** Time label shown before a message after a long pause or a change of day. */
    data class DateSeparator(val at: Instant) : TimelineItem {
        override val key: String get() = "date-${at.toEpochMilli()}"
    }

    data class Message(
        val segmentId: Uuid,
        val node: MessageNode,
        val at: Instant,
        /** Stable per-segment index; the UI maps it to a topic color. */
        val topicIndex: Int,
        /** Quote bar shown on top of the bubble, or null. */
        val quote: Quote?,
        /** Same sender as the previous message without a separator: hide the avatar. */
        val groupedWithPrevious: Boolean,
        /** The segment is generating and this is its newest message. */
        val streaming: Boolean,
        /** The newest settled assistant reply of its segment. */
        val canRegenerate: Boolean,
    ) : TimelineItem {
        val message: UIMessage get() = node.message
        val ref: MessageRef get() = MessageRef(segmentId, node.message.id)
        override val key: String get() = "msg-${node.message.id}"
    }

    /** A system line such as a self-configuration or memory change (filled by the revision history). */
    data class Notice(val notice: ThreadNotice) : TimelineItem {
        override val key: String get() = "notice-${notice.id}"
    }

    /** A segment is waiting for its first reply token. */
    data class Typing(val segmentIds: Set<Uuid>) : TimelineItem {
        override val key: String get() = "typing"
    }
}

data class Quote(
    val ref: MessageRef,
    val preview: String,
    val topicIndex: Int,
    /** The user chose to reply to this message, as opposed to an automatically shown trigger. */
    val explicit: Boolean,
)

enum class ThreadNoticeKind {
    /** The assistant changed its own settings or abilities. */
    SETTINGS,

    /** The assistant remembered or updated a memory. */
    MEMORY,

    /** The assistant removed a memory. */
    MEMORY_FORGOTTEN,
}

data class ThreadNotice(
    val id: String,
    val at: Instant,
    val kind: ThreadNoticeKind,
    val summary: String,
    /** The user message that caused the change; the notice is placed right after it. */
    val trigger: MessageRef?,
    val revisionId: String?,
    val undoable: Boolean,
)

object ThreadTimeline {
    /** A pause longer than this starts a new time label. */
    const val SEPARATOR_GAP_MILLIS = 30 * 60 * 1000L

    /** Same-sender messages further apart than this are not grouped. */
    const val GROUP_GAP_MILLIS = 5 * 60 * 1000L

    private val visibleRoles = setOf(MessageRole.USER, MessageRole.ASSISTANT)

    fun build(
        segments: List<Conversation>,
        notices: List<ThreadNotice> = emptyList(),
        generatingSegmentIds: Set<Uuid> = emptySet(),
        filterSegmentId: Uuid? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimelineItem> {
        val topicIndex = segments.sortedBy { it.createAt }.withIndex().associate { (index, segment) -> segment.id to index }
        val kotlinZone = TimeZone.of(zone.id)

        data class Entry(val segment: Conversation, val node: MessageNode, val at: Instant, val order: Int)

        val seenMessages = HashSet<Uuid>()
        var order = 0
        val entries = segments
            .filter { filterSegmentId == null || it.id == filterSegmentId }
            .flatMap { segment ->
                segment.currentMessageNodes.mapNotNull { node ->
                    if (node.role !in visibleRoles || !seenMessages.add(node.message.id)) return@mapNotNull null
                    val at = Instant.ofEpochMilli(node.message.createdAt.toInstant(kotlinZone).toEpochMilliseconds())
                    Entry(segment, node, at, order++)
                }
            }
            .sortedWith(compareBy<Entry> { it.at }.thenBy { it.order })

        val byMessageId = entries.associateBy { it.node.message.id }
        val indexByNodeId = entries.withIndex().associate { (index, entry) -> entry.node.id to index }

        // Each reply is quoted when it belongs to a batch of overlapping question → reply spans
        // that involves more than one segment, so a parallel batch reads consistently.
        val triggerIndex = IntArray(entries.size) { -1 }
        for ((index, entry) in entries.withIndex()) {
            if (entry.node.role != MessageRole.ASSISTANT) continue
            val trigger = entry.segment.currentMessageNodes
                .takeWhile { it.id != entry.node.id }
                .lastOrNull { it.role == MessageRole.USER }
                ?: continue
            triggerIndex[index] = indexByNodeId[trigger.id] ?: -1
        }
        val quoted = BooleanArray(entries.size)
        val spans = entries.indices
            .filter { triggerIndex[it] >= 0 }
            .map { triggerIndex[it]..it }
            .sortedBy { it.first }
        var batchStart = -1
        var batchEnd = -1
        fun closeBatch() {
            if (batchStart < 0) return
            val segmentsInBatch = (batchStart..batchEnd).mapTo(HashSet()) { entries[it].segment.id }
            if (segmentsInBatch.size > 1) {
                for (index in batchStart..batchEnd) if (triggerIndex[index] >= 0) quoted[index] = true
            }
        }
        for (span in spans) {
            if (span.first > batchEnd) {
                closeBatch()
                batchStart = span.first
                batchEnd = span.last
            } else {
                batchEnd = maxOf(batchEnd, span.last)
            }
        }
        closeBatch()

        val newestBySegment = entries.groupBy { it.segment.id }.mapValues { (_, list) -> list.last().node }
        val newestReplyBySegment = entries
            .filter { it.node.role == MessageRole.ASSISTANT }
            .groupBy { it.segment.id }
            .mapValues { (_, list) -> list.last().node.id }

        val noticesByTrigger = notices.filter { it.trigger != null && it.trigger.messageId in byMessageId }
            .groupBy { it.trigger!!.messageId }
        // Notices whose trigger is not loaded (an older page or another thread) are not shown.
        val looseNotices = notices.filter { it.trigger == null }
            .sortedBy { it.at }
            .toMutableList()

        val items = ArrayList<TimelineItem>(entries.size + notices.size + 8)
        var previous: Entry? = null
        var previousWasNotice = false

        fun addLooseNoticesBefore(at: Instant?) {
            while (looseNotices.isNotEmpty() && (at == null || !looseNotices.first().at.isAfter(at))) {
                items += TimelineItem.Notice(looseNotices.removeAt(0))
                previousWasNotice = true
            }
        }

        for ((index, entry) in entries.withIndex()) {
            addLooseNoticesBefore(entry.at)
            val last = previous
            val separated = last == null ||
                entry.at.toEpochMilli() - last.at.toEpochMilli() > SEPARATOR_GAP_MILLIS ||
                entry.at.atZone(zone).toLocalDate() != last.at.atZone(zone).toLocalDate()
            if (separated) items += TimelineItem.DateSeparator(entry.at)

            val grouped = !separated && !previousWasNotice && last != null &&
                last.node.role == entry.node.role &&
                entry.at.toEpochMilli() - last.at.toEpochMilli() <= GROUP_GAP_MILLIS

            val explicit = entry.node.replyTo?.let { ref ->
                val target = byMessageId[ref.messageId] ?: return@let null
                Quote(ref, target.node.message.previewText(), topicIndex.getValue(target.segment.id), explicit = true)
            }
            val automatic = if (quoted[index]) {
                val trigger = entries[triggerIndex[index]]
                Quote(
                    MessageRef(trigger.segment.id, trigger.node.message.id),
                    trigger.node.message.previewText(),
                    topicIndex.getValue(trigger.segment.id),
                    explicit = false,
                )
            } else {
                null
            }
            val generating = entry.segment.id in generatingSegmentIds
            items += TimelineItem.Message(
                segmentId = entry.segment.id,
                node = entry.node,
                at = entry.at,
                topicIndex = topicIndex.getValue(entry.segment.id),
                quote = explicit ?: automatic,
                groupedWithPrevious = grouped,
                streaming = generating && newestBySegment[entry.segment.id]?.id == entry.node.id,
                canRegenerate = !generating && newestReplyBySegment[entry.segment.id] == entry.node.id,
            )
            previous = entry
            previousWasNotice = false

            noticesByTrigger[entry.node.message.id]?.sortedBy { it.at }?.forEach {
                items += TimelineItem.Notice(it)
                previousWasNotice = true
            }
        }
        addLooseNoticesBefore(null)

        // Once a reply message exists, its streaming bubble replaces the typing row.
        val waiting = generatingSegmentIds.filterTo(HashSet()) { segmentId ->
            (filterSegmentId == null || filterSegmentId == segmentId) &&
                newestBySegment[segmentId]?.role != MessageRole.ASSISTANT
        }
        if (waiting.isNotEmpty()) items += TimelineItem.Typing(waiting)
        return items
    }
}

/** First text of a message on one line, used by quote bars and list previews. */
fun UIMessage.previewText(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .joinToString(" ") { it.text }
    .replace(Regex("\\s+"), " ")
    .trim()
