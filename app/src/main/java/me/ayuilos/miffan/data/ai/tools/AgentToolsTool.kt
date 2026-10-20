package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.RemoteTaskScript
import me.ayuilos.miffan.data.remotetask.ResultExtraction
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import kotlin.uuid.Uuid

const val AGENT_TOOLS_TOOL = "agent_tools"

/**
 * Lets a partner add and maintain the user's agent tools (docs/REMOTE_SCHEDULED_TASKS_PLAN.md).
 * Who writes a command line was never the safety boundary — a command task is any shell command —
 * the owner's approval of each change is, so every save and delete asks, with no standing grant.
 */
fun createAgentToolsTool(
    hostId: String,
    computerName: String,
    assistantId: Uuid,
    snapshot: WorkspaceToolTargetSnapshot?,
    tools: () -> List<AgentToolDefinition>,
    store: suspend (List<AgentToolDefinition>) -> Unit,
    repository: RemoteTaskRepository,
): Tool = Tool(
    name = AGENT_TOOLS_TOOL,
    description = "Manage the user's agent tools: command-line AI agents on $computerName that scheduled tasks can run " +
        "(see $SCHEDULE_TASK_TOOL). Actions: list, save (add, or change one you pass tool_id for), delete. Built-in " +
        "presets cannot be changed; save a copy under a new name instead. Every save and delete asks the user.",
    parameters = { agentToolsSchema() },
    needsApproval = { args -> (args as? JsonObject)?.text("action") in setOf("save", "delete") },
    // Names the computer, so the approval card can check the executable there.
    workspaceTarget = snapshot,
    systemPrompt = { _, _ ->
        buildString {
            appendLine("## Agent tools")
            appendLine("Before saving one, check it on $computerName with your command tools: that it is installed, its version, and " +
                "its --help for running without questions (non-interactive), how it takes the prompt, and its read-only and " +
                "file-changing modes.")
            appendLine("- Offer a read-only permission level first. Add a level that skips every confirmation only when the user " +
                "explicitly asks for it, and say plainly what it allows.")
            appendLine("- Changing a tool does not change existing tasks unless update_tasks is true (the default).")
        }
    },
    execute = { args ->
        val input = args as? JsonObject ?: JsonObject(emptyMap())
        try {
            when (input.text("action")) {
                "list" -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("status", "ok")
                    putJsonArray("tools") { tools().forEach { add(describe(it)) } }
                }.toString()))
                "save" -> {
                    val current = tools()
                    val definition = parseAgentToolDefinition(input, current, assistantId)
                    store(current.filterNot { it.id == definition.id }.filterNot { it.isPreset } + definition)
                    val found = runCatching { repository.resolveExecutable(hostId, definition) }.getOrNull()
                    val failed = if (input.bool("update_tasks") != false && current.any { it.id == definition.id })
                        repository.retarget(definition.id) else emptyMap()
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("status", "ok")
                        put("tool_id", definition.id)
                        found?.let { exe ->
                            put("found_on_computer", exe.path != null)
                            exe.path?.let { put("path", it) }
                            exe.version?.let { put("version", it) }
                            if (exe.path == null) put("warning", "${definition.executable} was not found on $computerName; tasks using it will fail until it is installed or the path is fixed.")
                        }
                        if (failed.isNotEmpty()) putJsonObject("tasks_not_updated") { failed.forEach { (name, why) -> put(name, why) } }
                    }.toString()))
                }
                "delete" -> {
                    val id = requireNotNull(input.text("tool_id")) { "tool_id is required" }
                    val current = tools()
                    val tool = requireNotNull(current.find { it.id == id }) { "No tool with that id" }
                    require(!tool.isPreset) { "Built-in presets cannot be deleted" }
                    store(current.filterNot { it.id == id || it.isPreset })
                    val using = repository.tasksUsing(id).size
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("status", "ok")
                        if (using > 0) put("note", "$using task(s) still use it and keep running as saved; they cannot be updated until they use another tool.")
                    }.toString()))
                }
                else -> listOf(UIMessagePart.Text("""{"status":"error","reason":"Unknown action. Use list, save or delete."}"""))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            listOf(UIMessagePart.Text(buildJsonObject { put("status", "error"); put("reason", error.message ?: "Invalid arguments") }.toString()))
        }
    },
)

/** Builds a definition from tool arguments. Pure, so the rules are testable. */
internal fun parseAgentToolDefinition(input: JsonObject, current: List<AgentToolDefinition>, assistantId: Uuid): AgentToolDefinition {
    val id = input.text("tool_id")
    val existing = id?.let { requireNotNull(current.find { tool -> tool.id == it }) { "No tool with that id" } }
    require(existing?.isPreset != true) { "Built-in presets cannot be changed; save a copy under a new name instead" }
    val name = requireNotNull(input.text("name")?.trim()?.takeIf { it.isNotEmpty() }) { "name is required" }
    current.find { it.name.equals(name, ignoreCase = true) && it.id != existing?.id }?.let {
        throw IllegalArgumentException("Another tool is already called ${it.name}")
    }
    val permissions = (input["permissions"] as? JsonArray).orEmpty().map { element ->
        val obj = element as? JsonObject ?: throw IllegalArgumentException("permissions must be objects")
        AgentToolDefinition.Permission(
            name = requireNotNull(obj.text("name")?.trim()?.takeIf { it.isNotEmpty() }) { "Each permission needs a name" },
            args = obj.strings("args"),
            writes = obj.bool("writes") ?: false,
        )
    }
    val definition = AgentToolDefinition(
        id = existing?.id ?: Uuid.random().toString(),
        name = name,
        executable = requireNotNull(input.text("executable")) { "executable is required" },
        args = input.strings("args"),
        permissions = permissions,
        extraction = parseExtraction(input["result"] as? JsonObject),
        versionArg = input.text("version_arg"),
        extraEnv = input.strings("extra_env"),
        // A change keeps who added the tool; only a new one is credited to this partner.
        addedBy = if (existing != null) existing.addedBy else assistantId,
    )
    definition.validate()
    return definition
}

private fun parseExtraction(obj: JsonObject?): ResultExtraction = when (obj?.text("type")) {
    null, "text" -> ResultExtraction.Text
    "json" -> ResultExtraction.JsonField(
        field = requireNotNull(obj.text("field")) { "result.field is required for json" },
        errorFlag = obj.text("error_flag"),
        errorField = obj.text("error_field"),
    )
    "jsonl" -> ResultExtraction.JsonLines(
        match = obj.stringMap("match").also { require(it.isNotEmpty()) { "result.match is required for jsonl" } },
        field = requireNotNull(obj.text("field")) { "result.field is required for jsonl" },
        errorMatch = obj.stringMap("error_match").takeIf { it.isNotEmpty() },
        errorField = obj.text("error_field"),
    )
    else -> throw IllegalArgumentException("result.type must be text, json or jsonl")
}

private fun describe(tool: AgentToolDefinition) = buildJsonObject {
    put("tool_id", tool.id)
    put("name", tool.name)
    put("built_in", tool.isPreset)
    put("command", (listOf(tool.executable) + tool.args).joinToString(" ") { RemoteTaskScript.quote(it) })
    putJsonArray("permissions") {
        tool.permissions.forEach { level ->
            add(buildJsonObject { put("name", level.name); put("adds", level.args.joinToString(" ")); put("writes", level.writes) })
        }
    }
}

private fun agentToolsSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("list"); add("save"); add("delete") } }
        putJsonObject("tool_id") { put("type", "string"); put("description", "To change or delete an existing tool; omit to add one.") }
        putJsonObject("name") { put("type", "string") }
        putJsonObject("executable") { put("type", "string"); put("description", "Command name (resolved with the user's login shell) or absolute path.") }
        putJsonObject("args") {
            put("type", "array"); putJsonObject("items") { put("type", "string") }
            put("description", "Arguments for a non-interactive run. One argument exactly \"{prompt}\" receives the task; without it the task goes to stdin.")
        }
        putJsonObject("permissions") {
            put("type", "array")
            put("description", "Named levels whose args are appended, e.g. read_only and workspace_write. writes=true when it can change files.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("args") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    putJsonObject("writes") { put("type", "boolean") }
                }
            }
        }
        putJsonObject("result") {
            put("type", "object")
            put("description", "Where the final answer is in its output. text: the whole output. json: field (dot path) of the last JSON " +
                "line, error_flag/error_field for failures. jsonl: field of the last event matching every match path=value, error_match/error_field for failures.")
        }
        putJsonObject("version_arg") { put("type", "string"); put("description", "e.g. --version") }
        putJsonObject("extra_env") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Extra variable names to inherit from the login shell (PATH and proxies always are).") }
        putJsonObject("update_tasks") { put("type", "boolean"); put("description", "When changing a tool, also update the tasks that use it (default true).") }
    },
    required = listOf("action"),
)

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray).orEmpty().map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("$key must be a list of strings")
}
private fun JsonObject.stringMap(key: String): Map<String, String> = (get(key) as? JsonObject)?.mapValues { (k, v) ->
    (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key.$k must be a string")
}.orEmpty()
