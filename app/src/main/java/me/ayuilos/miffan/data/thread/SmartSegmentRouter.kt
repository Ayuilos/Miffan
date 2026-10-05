package me.ayuilos.miffan.data.thread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** A segment offered to the classifier, labelled by its title or its latest exchange. */
data class TopicCandidate(val segmentId: Uuid, val label: String)

sealed interface TopicClassification {
    data class Existing(val index: Int) : TopicClassification
    data class New(val confidence: Double) : TopicClassification
}

/** Decides whether a message continues one of the candidate topics. Returns null when unavailable. */
fun interface TopicClassifier {
    suspend fun classify(message: String, candidates: List<TopicCandidate>): TopicClassification?
}

/**
 * Rules first, then a fast-model topic check. Every failure path continues the newest segment,
 * because a wrong split loses context while a wrong merge only adds unrelated history.
 */
class SmartSegmentRouter(
    private val classifier: TopicClassifier,
    private val idleMillis: Long = RuleSegmentRouter.DEFAULT_IDLE_MILLIS,
    private val maxSegmentChars: Int = MAX_SEGMENT_CHARS,
    private val maxOpenSegments: Int = MAX_OPEN_SEGMENTS,
) : SegmentRouter {
    override suspend fun route(request: RouteRequest): RouteDecision {
        val now = request.now.toEpochMilli()
        val open = request.segments.filter { it.threadClosedAt == 0L }.sortedByDescending { it.updateAt }
        val newest = open.firstOrNull() ?: return RouteDecision.New("no open segment")
        val stale = open.filterTo(HashSet()) { now - it.updateAt.toEpochMilli() > idleMillis }.mapTo(HashSet()) { it.id }
        if (newest.id in stale) return RouteDecision.New("thread idle", closeSegmentIds = stale)

        val text = request.content.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        val active = open.filter { it.id !in stale }
        if (newest.contentLength() > maxSegmentChars) {
            return RouteDecision.New("segment full", closeSegmentIds = stale + newest.id)
        }
        if (text.length < MIN_CLASSIFIED_CHARS) {
            return RouteDecision.Existing(newest.id, "short follow-up")
        }

        val candidates = active.take(maxOpenSegments).map { TopicCandidate(it.id, it.topicLabel()) }
        return when (val result = classifier.classify(text, candidates)) {
            null -> RouteDecision.Existing(newest.id, "classifier unavailable")
            is TopicClassification.Existing -> candidates.getOrNull(result.index)
                ?.let { RouteDecision.Existing(it.segmentId, "same topic") }
                ?: RouteDecision.Existing(newest.id, "invalid topic index")
            is TopicClassification.New -> if (result.confidence >= NEW_TOPIC_CONFIDENCE) {
                // Parallel topics stay open; only the oldest beyond the limit is closed.
                val overflow = active.drop(maxOpenSegments - 1).mapTo(HashSet()) { it.id }
                RouteDecision.New("new topic (${result.confidence})", closeSegmentIds = stale + overflow)
            } else {
                RouteDecision.Existing(newest.id, "uncertain new topic (${result.confidence})")
            }
        }
    }

    companion object {
        const val MAX_SEGMENT_CHARS = 60_000
        const val MAX_OPEN_SEGMENTS = 3
        const val MIN_CLASSIFIED_CHARS = 4
        const val NEW_TOPIC_CONFIDENCE = 0.7
    }
}

internal fun Conversation.contentLength(): Int =
    currentMessages.sumOf { message -> message.parts.sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 } }

/** Title when available, otherwise the latest question and answer of the segment. */
internal fun Conversation.topicLabel(): String {
    if (title.isNotBlank()) return title
    val visible = currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
    return visible.takeLast(2).joinToString(" / ") { it.previewText().take(120) }
}

/** Asks the fast model which candidate topic a message belongs to. */
class LlmTopicClassifier(
    private val models: ThreadModels,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : TopicClassifier {
    override suspend fun classify(message: String, candidates: List<TopicCandidate>): TopicClassification? {
        if (candidates.isEmpty()) return TopicClassification.New(1.0)
        val answer = models.fast(buildPrompt(message, candidates), timeoutMillis) ?: return null
        return parseClassification(answer, candidates.size)
    }

    companion object {
        const val TIMEOUT_MILLIS = 1_500L

        internal fun buildPrompt(message: String, candidates: List<TopicCandidate>): String = buildString {
            appendLine("You route chat messages to conversation topics. Ongoing topics:")
            candidates.forEachIndexed { index, candidate -> appendLine("$index: ${candidate.label.take(300)}") }
            appendLine()
            appendLine("New message: ${message.take(1000)}")
            appendLine()
            appendLine("If the message continues, answers, refers to, or plausibly relates to a topic, choose that topic.")
            appendLine("Choose \"new\" only when it is clearly unrelated to every topic.")
            append("Reply with JSON only: {\"topic\": <index or \"new\">, \"confidence\": <0 to 1>}")
        }

        internal fun parseClassification(answer: String, candidateCount: Int): TopicClassification? {
            val json = answer.substringAfter('{', "").substringBeforeLast('}', "")
                .takeIf { it.isNotEmpty() }?.let { "{$it}" } ?: return null
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val topic = obj["topic"]?.jsonPrimitive ?: return null
            val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0
            return if (topic.isString && topic.content.equals("new", ignoreCase = true)) {
                TopicClassification.New(confidence)
            } else {
                topic.content.toIntOrNull()?.takeIf { it in 0 until candidateCount }?.let { TopicClassification.Existing(it) }
            }
        }
    }
}
