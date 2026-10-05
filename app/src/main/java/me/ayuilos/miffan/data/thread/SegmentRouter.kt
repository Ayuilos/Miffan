package me.ayuilos.miffan.data.thread

import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import kotlin.uuid.Uuid

data class RouteRequest(
    val assistant: Assistant,
    val content: List<UIMessagePart>,
    /** Loaded segments of the thread, newest first. */
    val segments: List<Conversation>,
    val now: Instant,
)

sealed interface RouteDecision {
    val reason: String

    data class Existing(val segmentId: Uuid, override val reason: String) : RouteDecision

    /** Start a new segment; [closeSegmentIds] stop receiving new topics and get summarized. */
    data class New(override val reason: String, val closeSegmentIds: Set<Uuid> = emptySet()) : RouteDecision
}

/** Chooses which segment of an IM thread receives a new user message. */
fun interface SegmentRouter {
    suspend fun route(request: RouteRequest): RouteDecision
}

/**
 * Deterministic baseline: continue the newest open segment unless the thread has been idle.
 * Ambiguity favors continuing, because a wrong split loses context while a wrong merge only
 * adds unrelated history.
 */
class RuleSegmentRouter(private val idleMillis: Long = DEFAULT_IDLE_MILLIS) : SegmentRouter {
    override suspend fun route(request: RouteRequest): RouteDecision {
        val newest = request.segments.firstOrNull { it.threadClosedAt == 0L }
            ?: return RouteDecision.New("no open segment")
        val idle = request.now.toEpochMilli() - newest.updateAt.toEpochMilli()
        return if (idle > idleMillis) {
            RouteDecision.New("idle ${idle / 60_000} min", closeSegmentIds = setOf(newest.id))
        } else {
            RouteDecision.Existing(newest.id, "continue newest segment")
        }
    }

    companion object {
        const val DEFAULT_IDLE_MILLIS = 2 * 60 * 60 * 1000L
    }
}
