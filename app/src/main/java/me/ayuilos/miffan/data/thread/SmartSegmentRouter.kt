package me.ayuilos.miffan.data.thread

import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.model.TopicStart
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** A segment offered to the classifier, labelled by its title and latest exchange. */
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
    private val candidateIdleMillis: Long = CANDIDATE_IDLE_MILLIS,
    private val maxSegmentChars: Int = MAX_SEGMENT_CHARS,
    private val maxOpenSegments: Int = MAX_OPEN_SEGMENTS,
    private val hardIdleMillis: Long = HARD_IDLE_MILLIS,
) : SegmentRouter {
    override suspend fun route(request: RouteRequest): RouteDecision {
        val now = request.now.toEpochMilli()
        val open = request.segments.filter { it.chatId == null && it.threadClosedAt == 0L }.sortedByDescending { it.updateAt }
        val newest = open.firstOrNull() ?: return RouteDecision.New("no open segment", start = TopicStart.AUTO.takeIf { request.segments.any { it.chatId == null } })
        val stale = open.filter { now - it.updateAt.toEpochMilli() > candidateIdleMillis }
            .mapTo(HashSet()) { it.id }
        if (now - newest.updateAt.toEpochMilli() > hardIdleMillis) {
            return RouteDecision.New("thread idle", closeSegmentIds = stale + newest.id, start = TopicStart.IDLE)
        }

        val text = request.content.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        // Keep the newest idle segment eligible until a new topic is confirmed.
        val active = open.filter { it.id == newest.id || it.id !in stale }
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
                // Recent parallel topics stay open; idle topics and overflow are closed.
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
        const val CANDIDATE_IDLE_MILLIS = 2 * 60 * 60 * 1000L
        const val HARD_IDLE_MILLIS = 12 * 60 * 60 * 1000L
        const val NEW_TOPIC_CONFIDENCE = 0.8
    }
}

internal fun Conversation.contentLength(): Int =
    currentMessages.sumOf { message -> message.parts.sumOf { (it as? UIMessagePart.Text)?.text?.length ?: 0 } }

/** Title together with the latest question and answer, since a topic can outgrow its title. */
internal fun Conversation.topicLabel(): String {
    val visible = currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
    val exchange = listOfNotNull(
        visible.lastOrNull { it.role == MessageRole.USER },
        visible.lastOrNull { it.role == MessageRole.ASSISTANT },
    ).joinToString(" / ") { message ->
        val speaker = if (message.role == MessageRole.USER) "User" else "You"
        "$speaker: ${message.previewText().take(120)}"
    }
    return listOf(title.take(120), exchange).filter { it.isNotBlank() }.joinToString(" — ")
}

/** Asks the fast model which candidate topic a message belongs to. */
class LlmTopicClassifier(
    private val models: ThreadModels,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : TopicClassifier {
    override suspend fun classify(message: String, candidates: List<TopicCandidate>): TopicClassification? {
        if (candidates.isEmpty()) return TopicClassification.New(1.0)
        // A routing decision needs no deliberation; reasoning would only add latency.
        val answer = models.fast(buildPrompt(message, candidates), timeoutMillis, ReasoningLevel.OFF) ?: return null
        return parseClassification(answer, candidates.size)
    }

    companion object {
        // Measured on OpenRouter: 1.6–3 s when idle, 6–8+ s while other replies stream. The typing
        // row shows meanwhile, and waiting beats filing the message under the wrong topic.
        const val TIMEOUT_MILLIS = 15_000L

        internal fun buildPrompt(message: String, candidates: List<TopicCandidate>): String = buildString {
            appendLine("You route chat messages to conversation topics. Ongoing topics:")
            candidates.forEachIndexed { index, candidate -> appendLine("$index: ${candidate.label.take(400)}") }
            appendLine()
            appendLine("New message: ${message.take(1000)}")
            appendLine()
            appendLine("If the message continues, answers, refers to, or plausibly relates to a topic, choose that topic.")
            appendLine("Topic 0 is the current or most recent conversation. When unsure, choose 0 to continue it.")
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
