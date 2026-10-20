package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.db.entity.RemoteTaskEntity
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.RemoteTaskCommandException
import me.ayuilos.miffan.data.remotetask.RemoteTaskDraft
import me.ayuilos.miffan.data.remotetask.RemoteTaskMeta
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.RemoteTasksUnsupportedException
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.data.remotetask.TaskNotify
import me.ayuilos.miffan.data.remotetask.TaskSchedule
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import kotlin.uuid.Uuid

const val SCHEDULE_TASK_TOOL = "schedule_task"

/** Actions that change the computer; every one of them asks the owner, with no standing approval. */
private val CHANGING_ACTIONS = setOf("create", "update", "delete", "run_now", "enable", "disable")

/**
 * Lets a partner keep scheduled tasks on its computer (docs/REMOTE_SCHEDULED_TASKS_PLAN.md). The
 * computer's own timer runs them; results reach this chat when the user next opens Miffan.
 */
fun createScheduleTaskTool(
    hostId: String,
    computerName: String,
    windows: Boolean,
    assistantId: Uuid,
    conversationId: Uuid,
    snapshot: WorkspaceToolTargetSnapshot?,
    agentTools: () -> List<AgentToolDefinition>,
    timezone: String?,
    repository: RemoteTaskRepository,
): Tool {
    if (windows) return Tool(
        name = SCHEDULE_TASK_TOOL,
        description = "Scheduled tasks are not supported on Windows computers yet. If the user asks for one, " +
            "call this tool and explain the result.",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { listOf(text(refusal("Scheduled tasks are not supported on Windows computers yet. Tell the user; " +
            "they still work on Mac and Linux computers."))) },
    )
    return Tool(
        name = SCHEDULE_TASK_TOOL,
        description = "Keep scheduled tasks on $computerName: its own timer runs them at the set times even when the " +
            "phone is off. A task is a shell command, or a prompt given to one of the user's agent tools (listed in your " +
            "instructions). Actions: list, create, update, delete, run_now, enable, disable. Every change asks the user " +
            "for approval in the chat.",
        parameters = { scheduleTaskSchema() },
        needsApproval = { args -> args.action() in CHANGING_ACTIONS },
        workspaceTarget = snapshot,
        systemPrompt = { _, _ ->
            buildString {
                appendLine("## Scheduled tasks")
                appendLine("You can schedule tasks on $computerName with $SCHEDULE_TASK_TOOL. Times are the computer's local time" +
                    (timezone?.let { " ($it)" } ?: "") + ".")
                appendLine("- Results arrive in this chat when the user next opens Miffan, not the moment a task finishes; say so when it matters.")
                appendLine("- For an agent task pass agent_tool with one of these names and never write its command line yourself:")
                val tools = agentTools()
                if (tools.isEmpty()) appendLine("  (none)")
                tools.forEach { tool ->
                    val levels = tool.permissions.joinToString { it.name + if (it.writes) " (can change files)" else "" }
                    appendLine("  - ${tool.name}" + if (levels.isNotEmpty()) "; permission: $levels" else "")
                }
                appendLine("- Prefer read-only permission unless the task must change files.")
            }
        },
        execute = execute@{ args ->
            val input = args.jsonObject
            try {
                when (val action = args.action()) {
                    "list" -> listOf(text(listResult(runCatching { repository.refresh(hostId) }.exceptionOrNull(),
                        repository.cachedTasks(hostId), repository)))
                    "create", "update" -> {
                        val fields = if (action == "update") {
                            val taskId = input.requireTaskId()
                            val existing = repository.cachedTasks(hostId).find { it.taskId == taskId }
                                ?: repository.refresh(hostId).let { repository.cachedTasks(hostId).find { it.taskId == taskId } }
                                ?: throw IllegalArgumentException("No task $taskId on $computerName; call list for the task ids.")
                            val meta = requireNotNull(repository.decodeMeta(existing)) {
                                "Task $taskId was not made by Miffan and cannot be changed; delete it and create a new one."
                            }
                            mergeTaskUpdate(input, existing, meta, agentTools())
                        } else input
                        val draft = parseScheduleTaskDraft(fields, agentTools(), RemoteTaskMeta.Owner(assistantId, conversationId),
                            update = action == "update")
                        val taskId = repository.save(hostId, draft)
                        val testRun = input.bool("test_run") ?: defaultTestRun(action, draft.kind)
                        val trial = if (testRun) trialRun(repository, hostId, taskId) else null
                        listOf(text(buildJsonObject {
                            put("status", "ok")
                            put("task_id", taskId)
                            put("message", "Saved on $computerName. It runs on schedule even when the phone is off; results show up " +
                                "here when the user opens Miffan.")
                            trial?.let { put("test_run", it) }
                        }))
                    }
                    "delete" -> { repository.remove(hostId, input.requireTaskId()); listOf(text(ok("Deleted."))) }
                    "enable", "disable" -> {
                        repository.setEnabled(hostId, input.requireTaskId(), action == "enable")
                        listOf(text(ok(if (action == "enable") "Enabled." else "Paused; it no longer runs until enabled.")))
                    }
                    "run_now" -> listOf(text(buildJsonObject {
                        put("status", "ok")
                        put("test_run", trialRun(repository, hostId, input.requireTaskId()))
                    }))
                    else -> listOf(text(refusal("Unknown action. Use list, create, update, delete, run_now, enable or disable.")))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RemoteTasksUnsupportedException) {
                listOf(text(refusal("Scheduled tasks are not supported on Windows computers yet. Tell the user.")))
            } catch (error: RemoteTaskCommandException) {
                listOf(text(refusal(explain(error.detail))))
            } catch (error: IllegalArgumentException) {
                listOf(text(refusal(error.message ?: "Invalid arguments")))
            } catch (error: Exception) {
                listOf(text(refusal("Could not reach $computerName: ${error.message}. Tell the user; nothing was changed if this " +
                    "happened before saving.")))
            }
        },
    )
}

/**
 * An update as full arguments: the stored task overlaid with what the partner passed, so a call that
 * names only what changes ("move it to 9:00") keeps the rest. The schedule merges field by field.
 */
internal fun mergeTaskUpdate(input: JsonObject, existing: RemoteTaskEntity, meta: RemoteTaskMeta, agentTools: List<AgentToolDefinition>): JsonObject {
    val base = buildJsonObject {
        put("name", meta.name)
        put("kind", if (meta.kind == TaskKind.AGENT) "agent" else "command")
        meta.command?.let { put("command", it) }
        meta.agent?.let { agent ->
            // By id first: the tool may have been renamed since the task was saved.
            put("agent_tool", agentTools.find { it.id == agent.toolId }?.name ?: agent.toolName)
            agent.permission?.let { put("permission", it) }
        }
        meta.prompt?.let { put("prompt", it) }
        put("notify", meta.notify.name.lowercase())
        put("timeout_minutes", (existing.timeoutSeconds / 60).coerceAtLeast(1))
        TaskSchedule.parseHelper(existing.schedule)?.let { put("schedule", it.toInput()) }
    }
    val schedule = listOfNotNull(base["schedule"] as? JsonObject, input["schedule"] as? JsonObject)
        .takeIf { it.isNotEmpty() }?.reduce { old, new -> JsonObject(old + new) }
    return JsonObject(base + input + listOfNotNull(schedule?.let { "schedule" to it }))
}

private fun TaskSchedule.toInput(): JsonObject = buildJsonObject {
    fun time(hour: Int, minute: Int) = put("time", "%02d:%02d".format(hour, minute))
    when (this@toInput) {
        is TaskSchedule.Daily -> { put("type", "daily"); time(hour, minute) }
        is TaskSchedule.Weekly -> { put("type", "weekly"); putJsonArray("weekdays") { days.sorted().forEach { add(it) } }; time(hour, minute) }
        is TaskSchedule.Interval -> { put("type", "interval"); put("minutes", minutes) }
    }
}

/**
 * Whether a saved task runs once right away when nobody said: only a new agent task, where a missing
 * login or install would otherwise surface at its first scheduled time. The owner can change it on the card.
 */
internal fun defaultTestRun(action: String?, kind: TaskKind): Boolean = action == "create" && kind == TaskKind.AGENT

/** Turns tool arguments into a draft. Pure, so the rules are testable without a computer. */
internal fun parseScheduleTaskDraft(
    input: JsonObject,
    agentTools: List<AgentToolDefinition>,
    owner: RemoteTaskMeta.Owner,
    update: Boolean,
): RemoteTaskDraft {
    val taskId = if (update) input.requireTaskId() else null
    val name = requireNotNull(input.string("name")?.trim()?.takeIf { it.isNotEmpty() }) { "name is required" }
    val schedule = parseSchedule(input["schedule"] as? JsonObject ?: throw IllegalArgumentException("schedule is required"))
    val kind = when (input.string("kind")) {
        "command" -> TaskKind.COMMAND
        "agent" -> TaskKind.AGENT
        else -> throw IllegalArgumentException("kind must be command or agent")
    }
    val timeoutMinutes = input.int("timeout_minutes") ?: if (kind == TaskKind.AGENT) 30 else 10
    require(timeoutMinutes in 1..1440) { "timeout_minutes must be 1-1440" }
    val notify = when (input.string("notify")) {
        null -> if (kind == TaskKind.AGENT) TaskNotify.ALWAYS else TaskNotify.FAILURE
        "record" -> TaskNotify.RECORD
        "failure" -> TaskNotify.FAILURE
        "always" -> TaskNotify.ALWAYS
        else -> throw IllegalArgumentException("notify must be record, failure or always")
    }
    return when (kind) {
        TaskKind.COMMAND -> RemoteTaskDraft(
            taskId = taskId, name = name, kind = kind,
            command = requireNotNull(input.string("command")?.takeIf { it.isNotBlank() }) { "command is required for a command task" },
            schedule = schedule, timeoutSeconds = timeoutMinutes * 60, notify = notify, owner = owner,
        )
        TaskKind.AGENT -> {
            val toolName = requireNotNull(input.string("agent_tool")) { "agent_tool is required for an agent task" }
            val tool = requireNotNull(agentTools.find { it.name.equals(toolName, ignoreCase = true) }) {
                "No agent tool named $toolName. Available: ${agentTools.joinToString { it.name }}"
            }
            val permission = input.string("permission") ?: tool.permissions.firstOrNull { !it.writes }?.name
            require(tool.permissions.isEmpty() || tool.permissions.any { it.name == permission }) {
                "permission must be one of: ${tool.permissions.joinToString { it.name }}"
            }
            RemoteTaskDraft(
                taskId = taskId, name = name, kind = kind, agentToolId = tool.id,
                permission = permission.takeIf { tool.permissions.isNotEmpty() },
                prompt = requireNotNull(input.string("prompt")?.takeIf { it.isNotBlank() }) { "prompt is required for an agent task" },
                schedule = schedule, timeoutSeconds = timeoutMinutes * 60, notify = notify, owner = owner,
            )
        }
    }
}

private fun parseSchedule(obj: JsonObject): TaskSchedule {
    fun clock(): Pair<Int, Int> {
        val time = obj.string("time") ?: throw IllegalArgumentException("schedule.time is required (HH:MM)")
        val match = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])").matchEntire(time.trim())
            ?: throw IllegalArgumentException("schedule.time must be HH:MM in 24-hour time")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
    val schedule = when (obj.string("type")) {
        "daily" -> clock().let { (h, m) -> TaskSchedule.Daily(h, m) }
        "weekly" -> {
            val days = (obj["weekdays"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: -1 }?.toSet()
                ?: throw IllegalArgumentException("schedule.weekdays is required (0 = Sunday … 6 = Saturday)")
            clock().let { (h, m) -> TaskSchedule.Weekly(days, h, m) }
        }
        "interval" -> TaskSchedule.Interval(obj.int("minutes") ?: throw IllegalArgumentException("schedule.minutes is required"))
        else -> throw IllegalArgumentException("schedule.type must be daily, weekly or interval")
    }
    schedule.validate()
    return schedule
}

/**
 * Starts one run and waits up to [waitMillis] for its record, so the partner and user learn at once
 * whether the task works (an agent that is not logged in, a missing tool). A run that finishes in
 * time is marked delivered: it is already in this chat.
 */
private suspend fun trialRun(repository: RemoteTaskRepository, hostId: String, taskId: String, waitMillis: Long = 90_000): JsonObject {
    val started = System.currentTimeMillis()
    repository.runNow(hostId, taskId)
    while (System.currentTimeMillis() - started < waitMillis) {
        delay(5_000)
        val run = runCatching { repository.sync(hostId) }.getOrNull()?.lastOrNull { it.taskId == taskId && it.startedAt >= started - 60_000 }
        if (run != null) {
            repository.markDelivered(hostId, listOf(run.runId))
            return buildJsonObject {
                put("status", run.status)
                run.exitCode?.let { put("exit_code", it) }
                run.resultText?.let { put("result", it.take(4_000)) }
                run.resultError?.let { put("error", it.take(2_000)) }
                if (run.resultText == null && run.resultError == null) put("output_tail", run.excerpt.takeLast(2_000))
            }
        }
    }
    return buildJsonObject {
        put("status", "running")
        put("message", "Still running; its result will appear in this chat when the user next opens Miffan.")
    }
}

private suspend fun listResult(refreshError: Throwable?, tasks: List<RemoteTaskEntity>, repository: RemoteTaskRepository): String =
    buildJsonObject {
        put("status", "ok")
        refreshError?.let { put("note", "Could not reach the computer (${it.message}); this is the last known list.") }
        putJsonArray("tasks") {
            tasks.forEach { task ->
                val meta = repository.decodeMeta(task)
                add(buildJsonObject {
                    put("task_id", task.taskId)
                    put("name", meta?.name ?: "(unreadable)")
                    put("kind", meta?.kind?.name?.lowercase())
                    put("schedule", task.schedule)
                    put("enabled", task.enabled)
                    if (task.enabled && !task.timerOk) put("warning", "The computer's timer is not registered, so it will not run.")
                    meta?.command?.let { put("command", it) }
                    meta?.agent?.let { put("agent_tool", it.toolName); it.permission?.let { p -> put("permission", p) } }
                    meta?.prompt?.let { put("prompt", it) }
                })
            }
        }
    }.toString()

private fun explain(detail: String): String = when {
    detail == "login_required" -> "The Mac has no logged-in user session, so macOS will not run the task. Ask the user to log " +
        "in on that Mac, then try again."
    detail.startsWith("agent_missing:") -> "${detail.substringAfter(':')} was not found on the computer. Ask the user to install " +
        "it, or to set its full path in Miffan's agent tool settings."
    detail == "no scheduler available" -> "This computer has no task scheduler Miffan can use (no systemd user session or cron)."
    else -> "The computer refused: $detail"
}

private fun scheduleTaskSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        putJsonObject("action") {
            put("type", "string")
            putJsonArray("enum") { listOf("list", "create", "update", "delete", "run_now", "enable", "disable").forEach { add(it) } }
        }
        putJsonObject("task_id") {
            put("type", "string")
            put("description", "For update, delete, run_now, enable, disable. An update needs only the fields that change; the rest stay as they are.")
        }
        putJsonObject("name") { put("type", "string"); put("description", "Short name the user will recognise.") }
        putJsonObject("schedule") {
            put("type", "object")
            put("description", "Computer local time. daily: time. weekly: weekdays (0 = Sunday … 6 = Saturday) and time. " +
                "interval: minutes, one of ${TaskSchedule.INTERVAL_MINUTES.joinToString()}.")
            putJsonObject("properties") {
                putJsonObject("type") { put("type", "string"); putJsonArray("enum") { add("daily"); add("weekly"); add("interval") } }
                putJsonObject("time") { put("type", "string"); put("description", "HH:MM, 24-hour") }
                putJsonObject("weekdays") { put("type", "array"); putJsonObject("items") { put("type", "integer") } }
                putJsonObject("minutes") { put("type", "integer") }
            }
            putJsonArray("required") { add("type") }
        }
        putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("command"); add("agent") } }
        putJsonObject("command") { put("type", "string"); put("description", "For kind=command: run by /bin/sh in the computer's workspace folder.") }
        putJsonObject("agent_tool") { put("type", "string"); put("description", "For kind=agent: a name from your instructions.") }
        putJsonObject("permission") { put("type", "string"); put("description", "For kind=agent: a permission level of that tool.") }
        putJsonObject("prompt") { put("type", "string"); put("description", "For kind=agent: what the agent should do each time.") }
        putJsonObject("notify") {
            put("type", "string")
            put("description", "record: just log it; failure: you look at failed runs (default for commands); always: you report every run (default for agents).")
            putJsonArray("enum") { add("record"); add("failure"); add("always") }
        }
        putJsonObject("timeout_minutes") { put("type", "integer"); put("description", "Default 10 for commands, 30 for agents.") }
        putJsonObject("test_run") { put("type", "boolean"); put("description", "Suggest running once right after saving (default true for a new agent task, otherwise false). The user decides on the approval card.") }
    },
    required = listOf("action"),
)

private fun JsonElement.action(): String? = (this as? JsonObject)?.string("action")
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.requireTaskId(): String = requireNotNull(string("task_id")?.takeIf { it.isNotBlank() }) { "task_id is required" }
private fun ok(message: String) = buildJsonObject { put("status", "ok"); put("message", message) }
private fun refusal(message: String) = buildJsonObject { put("status", "error"); put("reason", message) }
private fun text(json: JsonObject) = UIMessagePart.Text(json.toString())
private fun text(json: String) = UIMessagePart.Text(json)
