package me.ayuilos.miffan.data.remotetask

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The files `task stage` receives for one task. */
data class RenderedTask(val run: String, val prompt: String, val env: String)

/**
 * Turns a task into the `run` script the helper executes with /bin/sh. Every value is single-quoted,
 * so nothing the partner or user typed is ever parsed as shell syntax except a command task's own
 * command.
 */
object RemoteTaskScript {
    fun quote(text: String): String {
        require('\u0000' !in text) { "Text cannot contain NUL" }
        return "'" + text.replace("'", "'\\''") + "'"
    }

    /** The argv an agent task runs, for execution and for the approval card. */
    fun agentArgv(tool: AgentToolDefinition, permission: String?, executablePath: String, prompt: String): List<String> {
        tool.validate()
        val level = permission?.let { name -> requireNotNull(tool.permissions.find { it.name == name }) { "Unknown permission $name" } }
        require(level != null || tool.permissions.isEmpty()) { "Choose a permission level" }
        val args = tool.args.map { if (it == AgentToolDefinition.PROMPT_PLACEHOLDER) prompt else it }
        return listOf(executablePath) + args + level?.args.orEmpty()
    }

    fun renderCommand(command: String, cwd: String, fallbackPath: String?, env: List<String> = DEFAULT_TASK_ENV): RenderedTask {
        require(command.isNotBlank()) { "Command is empty" }
        require('\u0000' !in command) { "Command cannot contain NUL" }
        return RenderedTask(header(cwd, fallbackPath) + command.trimEnd('\n') + "\n", prompt = "", env = env(env))
    }

    fun renderAgent(
        tool: AgentToolDefinition,
        permission: String?,
        prompt: String,
        cwd: String,
        executablePath: String,
        fallbackPath: String?,
    ): RenderedTask {
        require(prompt.isNotBlank()) { "Prompt is empty" }
        require(executablePath.startsWith("/")) { "Executable must be resolved to an absolute path" }
        val argv = agentArgv(tool, permission, executablePath, prompt)
        return RenderedTask(
            run = header(cwd, fallbackPath) + "exec " + argv.joinToString(" ") { quote(it) } + "\n",
            prompt = if (tool.takesPromptArgument) "" else prompt,
            env = env((DEFAULT_TASK_ENV + tool.extraEnv).distinct()),
        )
    }

    /** Whether an approval card should warn that the command line gives the agent the whole machine. */
    fun grantsFullAccess(argv: List<String>): Boolean = argv.any { it in AgentToolDefinition.DANGEROUS_ARGS }

    private fun header(cwd: String, fallbackPath: String?): String {
        require(cwd.startsWith("/")) { "Working directory must be absolute" }
        return buildString {
            appendLine("# Written by Miffan. Change this task in Miffan; edits here are replaced.")
            appendLine("cd ${quote(cwd)} || exit 97")
            // The helper exports the login shell's variables and sets MIFFAN_LOGIN_ENV=ok; keep its PATH.
            if (!fallbackPath.isNullOrBlank()) {
                appendLine("[ \"\${MIFFAN_LOGIN_ENV:-}\" = ok ] || { PATH=${quote(fallbackPath)}; export PATH; }")
            }
        }
    }

    private fun env(names: List<String>): String {
        require(names.size <= 64 && names.all { it.matches(AgentToolDefinition.ENV_NAME) }) { "Invalid variable names" }
        return names.joinToString("") { "$it\n" }
    }
}

/** What a finished run said: the agent's answer, or why it failed. Both null when nothing matched. */
data class ExtractedResult(val text: String?, val error: String?)

object RemoteTaskResults {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(output: String, extraction: ResultExtraction): ExtractedResult = when (extraction) {
        ResultExtraction.Text -> ExtractedResult(output.trim().ifEmpty { null }, null)
        is ResultExtraction.JsonField -> {
            val obj = output.lineSequence().toList().asReversed().firstNotNullOfOrNull { parseObject(it) }
            when {
                obj == null -> ExtractedResult(null, null)
                extraction.errorFlag != null && (obj.path(extraction.errorFlag) as? JsonPrimitive)?.booleanOrNull == true ->
                    ExtractedResult(null, extraction.errorField?.let { obj.path(it).text() } ?: "Failed")
                else -> ExtractedResult(obj.path(extraction.field).text(), null)
            }
        }
        is ResultExtraction.JsonLines -> {
            val events = output.lineSequence().mapNotNull { parseObject(it) }.toList()
            val answer = events.lastOrNull { event -> extraction.match.all { (path, value) -> event.path(path).text() == value } }
            val failure = extraction.errorMatch?.let { match ->
                events.lastOrNull { event -> match.all { (path, value) -> event.path(path).text() == value } }
            }
            ExtractedResult(
                answer?.path(extraction.field).text(),
                failure?.let { event -> extraction.errorField?.let { event.path(it).text() } ?: "Failed" },
            )
        }
    }

    private fun parseObject(line: String): JsonObject? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    }

    private fun JsonElement?.path(path: String): JsonElement? =
        path.split('.').fold(this) { element, key -> (element as? JsonObject)?.get(key) }

    private fun JsonElement?.text(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }
}

/** The zone a computer's schedules use: its IANA zone, else its current UTC offset. */
fun RemoteTaskProbe.zone(): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: runCatching { ZoneOffset.of(utcOffset.replaceRange(3, 3, ":")) }.getOrNull()
        ?: ZoneOffset.UTC

/**
 * The next time a daily or weekly schedule fires after [now], in the computer's zone. Null for
 * intervals: their phase depends on when the timer started, which only the computer knows.
 */
fun TaskSchedule.nextRun(now: ZonedDateTime): ZonedDateTime? {
    val time = localTime() ?: return null
    val days = (this as? TaskSchedule.Weekly)?.days?.map { it.toDayOfWeek() }?.toSet()
    return (0..7).asSequence()
        .map { now.toLocalDate().plusDays(it.toLong()).atTime(time).atZone(now.zone) }
        .first { it.isAfter(now) && (days == null || it.dayOfWeek in days) }
}
