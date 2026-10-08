package me.ayuilos.miffan.data.audit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.dao.AuditDAO
import me.ayuilos.miffan.data.db.entity.AuditEventEntity
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.service.approvalNotificationSummary
import me.rerere.ai.ui.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

enum class AuditKind { APPROVAL, SCREEN_TAKEN_OVER, SCREEN_HANDED_BACK }

data class AuditEvent(
    val id: String, val at: Long, val kind: AuditKind?,
    val assistantId: String?, val conversationId: String?, val messageId: String?,
    val toolCallId: String?, val hostId: String?, val hostName: String?, val toolName: String?,
    val summary: String, val decision: ToolDecision?, val via: ToolDecisionVia?, val requestedAt: Long?,
)

internal inline fun <reified T : Enum<T>> parseAuditEnum(value: String?): T? = enumValues<T>().find { it.name == value }

internal fun AuditEventEntity.toEvent() = AuditEvent(
    id, at, parseAuditEnum(kind), assistantId, conversationId, messageId, toolCallId,
    hostId, hostName, toolName, summary, parseAuditEnum(decision), parseAuditEnum(via), requestedAt,
)

/** Pure row building; only calls with a final, app-owned approval record qualify. */
internal fun approvalAuditRow(
    assistantId: Uuid?, conversationId: Uuid?, messageId: Uuid, tool: UIMessagePart.Tool,
    text: (Int, List<Any>) -> String,
): AuditEventEntity? {
    val record = tool.approvalRecord ?: return null
    val decision = record.decision ?: return null
    val at = record.decidedAt ?: return null
    return AuditEventEntity(
        id = Uuid.random().toString(), at = at, kind = AuditKind.APPROVAL.name,
        assistantId = assistantId?.toString(), conversationId = conversationId?.toString(),
        messageId = messageId.toString(), toolCallId = tool.toolCallId,
        hostId = tool.workspaceTarget?.remoteHostId,
        hostName = tool.workspaceTarget?.remoteHostName ?: tool.workspaceTarget?.remoteHostLabel,
        toolName = tool.toolName,
        summary = requireNotNull(approvalNotificationSummary(listOf(tool.copy(approvalState = ToolApprovalState.Pending)), text)),
        decision = decision.name, via = record.via?.name, requestedAt = record.requestedAt,
    )
}

/**
 * Best-effort app-lifetime recorder, serialized on its own IO scope. No database work on the
 * generation path. Failed writes are logged and never affect tools. Pending writes do not
 * survive process death; committed rows survive conversation, partner and host deletion.
 */
class AuditRepository(
    private val dao: AuditDAO,
    private val context: Context,
    private val workspaces: WorkspaceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) try { write() } catch (error: Exception) {
                Log.w("AuditRepository", "Could not record audit event", error)
            }
        }
    }

    /** Null partner selects all history; otherwise includes that partner plus screen events on hostIds. */
    fun observe(assistantId: Uuid?, hostIds: Set<String>, limit: Int): Flow<List<AuditEvent>> =
        dao.observe(assistantId?.toString(), hostIds, limit.coerceIn(0, 5000)).map { rows -> rows.map { it.toEvent() } }

    /** Null clears all history. A partner id clears only its rows, not shared screen events. */
    suspend fun clear(assistantId: Uuid?) {
        val result = CompletableDeferred<Unit>()
        writes.send {
            try { dao.clear(assistantId?.toString()); result.complete(Unit) }
            catch (error: Exception) { result.completeExceptionally(error) }
        }
        result.await()
    }

    fun recordApproval(assistantId: Uuid?, conversationId: Uuid?, messageId: Uuid, tool: UIMessagePart.Tool) {
        if (tool.approvalRecord?.decision == null) return
        // Capture the locale now; formatting and persistence happen off the caller's path.
        val resources = try {
            context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration)).resources
        } catch (error: Exception) {
            Log.w("AuditRepository", "Could not capture audit locale", error)
            return
        }
        writes.trySend {
            approvalAuditRow(assistantId, conversationId, messageId, tool) { id, args ->
                resources.getString(id, *args.toTypedArray())
            }?.let { dao.record(it) }
        }
    }

    /** Called at decision boundaries, not for each streamed token. */
    fun recordDecisions(conversation: Conversation, previous: Conversation) {
        val settled = previous.currentMessages.flatMap { it.getTools() }
            .filter { it.approvalRecord?.decision != null }.mapTo(HashSet()) { it.toolCallId }
        conversation.currentMessages.forEach { message ->
            message.getTools().filter { it.toolCallId !in settled }.forEach { recordApproval(conversation.assistantId, conversation.id, message.id, it) }
        }
    }

    fun recordScreen(hostId: String, takenOver: Boolean) {
        val at = Clock.System.now().toEpochMilliseconds()
        writes.trySend {
            val host = workspaces.getHostById(hostId)
            dao.record(AuditEventEntity(
                id = Uuid.random().toString(), at = at,
                kind = (if (takenOver) AuditKind.SCREEN_TAKEN_OVER else AuditKind.SCREEN_HANDED_BACK).name,
                hostId = hostId, hostName = host?.name,
                summary = context.getString(if (takenOver) R.string.im_computer_taken_over else R.string.workspace_screen_hand_back),
            ))
        }
    }
}
