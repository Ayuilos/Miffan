package me.ayuilos.miffan.data.remotetask

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.dao.RemoteTaskDAO
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.thread.ThreadService
import kotlin.uuid.Uuid

private const val TAG = "RemoteTaskDelivery"

/** [MessageOrigin.Event.kind] of a delivered task line; the failed variant shows as a failure. */
const val SCHEDULED_RUN = "scheduled_run"
const val SCHEDULED_RUN_FAILED = "scheduled_run_failed"

/** What came in for one partner while the user was away, for the banner atop its chat. */
data class TaskDeliverySummary(val hostId: String, val runs: Int, val failures: Int)

/**
 * Brings finished runs into their partners' chats when the app comes to the foreground; nothing
 * runs in the background (docs/REMOTE_SCHEDULED_TASKS_PLAN.md). Each task gets one tappable line.
 * The partner speaks once per delivery, and only when a task asks for it: every run (ALWAYS) or a
 * failed one (FAILURE). Record-only tasks never call the model.
 */
class RemoteTaskDelivery(
    private val context: Context,
    private val repository: RemoteTaskRepository,
    private val dao: RemoteTaskDAO,
    private val threads: () -> ThreadService,
) {
    private val lock = Mutex()
    private val _summaries = MutableStateFlow<Map<Uuid, TaskDeliverySummary>>(emptyMap())
    /** Per partner, until the user dismisses it. */
    val summaries: StateFlow<Map<Uuid, TaskDeliverySummary>> = _summaries

    fun dismiss(assistantId: Uuid) = _summaries.update { it - assistantId }

    /** Syncs computers that have tasks (throttled per computer), then delivers what is new. */
    suspend fun syncAndDeliver() = lock.withLock {
        runCatching { repository.syncAll() }.onFailure { Log.w(TAG, "sync failed", it) }
        runCatching { deliver() }.onFailure { Log.w(TAG, "delivery failed", it) }
    }

    private suspend fun deliver() {
        val pending = dao.undeliveredRuns()
        if (pending.isEmpty()) return
        data class Item(val run: RemoteTaskRunEntity, val meta: RemoteTaskMeta)
        val items = pending.mapNotNull { run ->
            val meta = dao.task(run.hostId, run.taskId)?.let(repository::decodeMeta) ?: return@mapNotNull null
            Item(run, meta)
        }
        // Runs of deleted or unreadable tasks stay in the task history only.
        val orphaned = pending.filter { run -> items.none { it.run === run } }
        orphaned.groupBy { it.hostId }.forEach { (host, runs) -> repository.markDelivered(host, runs.map { it.runId }) }
        for ((assistantId, group) in items.groupBy { it.meta.owner?.assistantId }) {
            if (assistantId == null) {
                group.groupBy { it.run.hostId }.forEach { (host, list) -> repository.markDelivered(host, list.map { it.run.runId }) }
                continue
            }
            val byTask = group.groupBy { it.run.hostId to it.run.taskId }.values.toList()
            val speaks = group.any { (run, meta) ->
                meta.notify == TaskNotify.ALWAYS || (meta.notify == TaskNotify.FAILURE && run.isFailure)
            }
            byTask.forEachIndexed { index, runs ->
                val last = runs.maxBy { it.run.runId }
                val origin = MessageOrigin.Event(
                    kind = if (runs.any { it.run.isFailure }) SCHEDULED_RUN_FAILED else SCHEDULED_RUN,
                    summary = lineFor(runs.map { it.run }, last.meta),
                    sourceAssistantId = assistantId,
                    link = "remote-task:${last.run.hostId}/${last.run.taskId}",
                )
                val details = runs.joinToString("\n\n") { describe(it.run, it.meta) }
                if (speaks && index == byTask.lastIndex) {
                    // The last line carries every result, so the partner answers all of them at once.
                    val all = byTask.flatten().joinToString("\n\n") { describe(it.run, it.meta) }
                    threads().deliverFrom(origin, assistantId, listOf(me.rerere.ai.ui.UIMessagePart.Text(instruction(all))))
                } else {
                    threads().deliverRecord(assistantId, origin, details)
                }
                runs.groupBy { it.run.hostId }.forEach { (host, list) -> repository.markDelivered(host, list.map { it.run.runId }) }
            }
            _summaries.update {
                it + (assistantId to TaskDeliverySummary(group.first().run.hostId, group.size, group.count { item -> item.run.isFailure }))
            }
        }
    }

    /** "Disk check · succeeded 4 times · ran 09:00 · synced 10:32", as the timeline shows it. */
    private fun lineFor(runs: List<RemoteTaskRunEntity>, meta: RemoteTaskMeta): String {
        val last = runs.maxBy { it.runId }
        val failures = runs.count { it.isFailure }
        val status = when {
            runs.size == 1 -> context.getString(if (last.isFailure) R.string.remote_task_status_failed else R.string.remote_task_status_ok)
            failures == 0 -> context.getString(R.string.remote_task_line_ok_times, runs.size)
            else -> context.getString(R.string.remote_task_line_failed_times, failures, runs.size)
        }
        return context.getString(R.string.remote_task_line, meta.name, status, clock(last.startedAt), clock(last.syncedAt))
    }

    private fun describe(run: RemoteTaskRunEntity, meta: RemoteTaskMeta): String = buildString {
        append("Task \"").append(meta.name).append("\" (")
        append(if (meta.kind == TaskKind.AGENT) "agent ${meta.agent?.toolName}" else "command").append(", notify=")
        append(meta.notify.name.lowercase()).append(") ran at ").append(isoLocal(run.startedAt))
        append(": ").append(run.status)
        run.exitCode?.let { append(", exit code ").append(it) }
        run.resultError?.let { append("\nFailure: ").append(it.take(1_500)) }
        run.resultText?.let { append("\nResult: ").append(it.take(3_000)) }
        if (run.resultText == null && run.resultError == null && run.excerpt.isNotBlank()) {
            append("\nOutput (end): ").append(run.excerpt.takeLast(1_500))
        }
    }

    private fun instruction(details: String) =
        "Results of scheduled tasks on the user's computer arrived now that the user opened Miffan; they ran earlier, " +
            "while the user was away. Tell the user briefly what matters: explain failures and offer a fix; mention " +
            "successes of tasks set to report every run. Do not repeat raw output unless asked.\n\n$details"

    private fun clock(epochMillis: Long) = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
    private fun isoLocal(epochMillis: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}

internal val RemoteTaskRunEntity.isFailure: Boolean get() = status != "ok" && status != "skipped_overlap"
