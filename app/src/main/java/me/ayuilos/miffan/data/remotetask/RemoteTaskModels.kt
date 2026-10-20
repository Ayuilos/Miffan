package me.ayuilos.miffan.data.remotetask

import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * When a task runs, in the computer's local time. Only shapes every timer backend (systemd,
 * launchd, cron) can express exactly; see remote-helper/PROTOCOL.md.
 */
@Serializable
sealed interface TaskSchedule {
    @Serializable
    @SerialName("daily")
    data class Daily(val hour: Int, val minute: Int) : TaskSchedule

    /** [days] use 0 = Sunday, as cron and launchd do. */
    @Serializable
    @SerialName("weekly")
    data class Weekly(val days: Set<Int>, val hour: Int, val minute: Int) : TaskSchedule

    @Serializable
    @SerialName("interval")
    data class Interval(val minutes: Int) : TaskSchedule

    fun validate() {
        when (this) {
            is Daily -> requireTime(hour, minute)
            is Weekly -> {
                require(days.isNotEmpty() && days.all { it in 0..6 }) { "Weekdays must be 0-6 (0 = Sunday)" }
                requireTime(hour, minute)
            }
            is Interval -> require(minutes in INTERVAL_MINUTES) { "Interval must be one of $INTERVAL_MINUTES minutes" }
        }
    }

    /** The words after `task commit <id> <timeout>`. */
    fun helperArgs(): List<String> {
        validate()
        return when (this) {
            is Daily -> listOf("daily", clock(hour, minute))
            is Weekly -> listOf("weekly", days.sorted().joinToString(","), clock(hour, minute))
            is Interval -> listOf("interval", minutes.toString())
        }
    }

    companion object {
        val INTERVAL_MINUTES = listOf(15, 30, 60, 120, 180, 240, 360, 480, 720)

        /** Parses the helper's one-line `schedule` file; null for anything else. */
        fun parseHelper(line: String): TaskSchedule? {
            val words = line.trim().split(' ')
            return runCatching {
                when (words.firstOrNull()) {
                    "daily" -> words.takeIf { it.size == 2 }?.let { parseClock(it[1]) }?.let { (h, m) -> Daily(h, m) }
                    "weekly" -> words.takeIf { it.size == 3 }?.let { w ->
                        parseClock(w[2])?.let { (h, m) -> Weekly(w[1].split(',').map(String::toInt).toSet(), h, m) }
                    }
                    "interval" -> words.takeIf { it.size == 2 }?.let { Interval(it[1].toInt()) }
                    else -> null
                }?.also { it.validate() }
            }.getOrNull()
        }

        private fun requireTime(hour: Int, minute: Int) =
            require(hour in 0..23 && minute in 0..59) { "Time must be HH:MM" }

        private fun clock(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

        private fun parseClock(text: String): Pair<Int, Int>? {
            val match = Regex("([01][0-9]|2[0-3]):([0-5][0-9])").matchEntire(text) ?: return null
            return match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }
    }
}

internal fun Int.toDayOfWeek(): DayOfWeek = if (this == 0) DayOfWeek.SUNDAY else DayOfWeek.of(this)

internal fun TaskSchedule.localTime(): LocalTime? = when (this) {
    is TaskSchedule.Daily -> LocalTime.of(hour, minute)
    is TaskSchedule.Weekly -> LocalTime.of(hour, minute)
    is TaskSchedule.Interval -> null
}

/** What the chat does with a finished run. */
@Serializable
enum class TaskNotify {
    /** A folded record in the timeline; the model is not called. */
    @SerialName("record") RECORD,
    /** The partner looks at failed runs. Default for command tasks. */
    @SerialName("failure") FAILURE,
    /** The partner reports every run. Default for agent tasks. */
    @SerialName("always") ALWAYS,
}

@Serializable
enum class TaskKind {
    @SerialName("command") COMMAND,
    @SerialName("agent") AGENT,
}

/**
 * The app's description of a task. The helper stores it verbatim (meta.json) and returns it from
 * `task list`, so the app can rebuild everything from the computer after a reinstall.
 */
@Serializable
data class RemoteTaskMeta(
    val version: Int = 1,
    val name: String,
    val kind: TaskKind,
    /** Shell command run by /bin/sh; only for [TaskKind.COMMAND]. */
    val command: String? = null,
    /** Only for [TaskKind.AGENT]: which tool and permission level, as they were when approved. */
    val agent: AgentRef? = null,
    val prompt: String? = null,
    /** Absolute directory on the computer the task runs in. */
    val cwd: String,
    /** Variable names read from the login shell at each run (values never leave the computer). */
    val env: List<String> = DEFAULT_TASK_ENV,
    val notify: TaskNotify,
    val owner: Owner? = null,
    /**
     * Fingerprint of the Miffan key of the phone that made the task ([sshKeyFingerprint]). Forgetting
     * Miffan with that key removes it; other phones' tasks on the same computer stay. Null on tasks
     * from builds before this field, which are treated as the forgetting phone's.
     */
    val sshKey: String? = null,
    val createdAt: Long,
) {
    @Serializable
    data class AgentRef(
        val toolId: String,
        /** Display name at creation, kept for when the definition is later renamed or deleted. */
        val toolName: String,
        val permission: String? = null,
        /** How results were read when the task was saved; still works after the tool is edited or deleted. */
        val extraction: ResultExtraction? = null,
    )

    /** Where results go. Null for tasks the user made without a partner. */
    @Serializable
    data class Owner(val assistantId: Uuid, val conversationId: Uuid? = null)
}

/** OpenSSH's SHA256 fingerprint of a public key line ("type base64 [comment]"), as `ssh-keygen -l` shows it. */
fun sshKeyFingerprint(publicKey: String): String {
    val blob = java.util.Base64.getDecoder().decode(publicKey.trim().split(Regex("\\s+"))[1])
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
        .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(blob))
}

/** PATH plus the proxy variables people set in shell startup files and timers never see. */
val DEFAULT_TASK_ENV: List<String> = listOf("PATH") +
    listOf("http_proxy", "https_proxy", "all_proxy", "no_proxy").flatMap { listOf(it, it.uppercase()) }

/** How a run's final answer is pulled out of an agent's output. */
@Serializable
sealed interface ResultExtraction {
    /** The whole output. */
    @Serializable
    @SerialName("text")
    data object Text : ResultExtraction

    /**
     * The last line that parses as a JSON object; [field] is a dot path, e.g. "result". The run
     * failed when [errorFlag] is true there (Claude Code reports auth errors with exit code 1 and
     * `is_error`), and then [errorField] explains why.
     */
    @Serializable
    @SerialName("json")
    data class JsonField(val field: String, val errorFlag: String? = "is_error", val errorField: String? = null) : ResultExtraction

    /**
     * Line-delimited JSON events: [field] of the last event whose dot paths equal every value in
     * [match]; failures likewise with [errorMatch]. Non-JSON lines (stderr noise) are skipped.
     */
    @Serializable
    @SerialName("jsonl")
    data class JsonLines(
        val match: Map<String, String>,
        val field: String,
        val errorMatch: Map<String, String>? = null,
        val errorField: String? = null,
    ) : ResultExtraction
}

/**
 * An agent command line the user can schedule. Built-in presets are code; the user, or a partner
 * with the user's approval of each change, adds their own. A task keeps the command line it was
 * saved with, so editing a definition never silently changes what existing tasks run.
 */
@Serializable
data class AgentToolDefinition(
    val id: String = Uuid.random().toString(),
    val name: String,
    /** Command name resolved on the computer, or an absolute path. */
    val executable: String,
    /** Arguments; one equal to [PROMPT_PLACEHOLDER] is replaced by the prompt, otherwise it goes to stdin. */
    val args: List<String>,
    /** Named argument sets appended after [args]; empty means the command line decides. */
    val permissions: List<Permission> = emptyList(),
    val extraction: ResultExtraction = ResultExtraction.Text,
    val versionArg: String? = null,
    /** Extra variables to inherit besides [DEFAULT_TASK_ENV]. */
    val extraEnv: List<String> = emptyList(),
    /** The partner that proposed it (the user approved); null when the user added it. */
    val addedBy: Uuid? = null,
) {
    @Serializable
    data class Permission(val name: String, val args: List<String>, val writes: Boolean = false)

    val takesPromptArgument: Boolean get() = args.any { it == PROMPT_PLACEHOLDER }
    val isPreset: Boolean get() = id.startsWith(PRESET_PREFIX)

    fun validate() {
        require(name.isNotBlank()) { "Name is empty" }
        require(executable.isNotBlank() && executable.none { it == '\n' || it == '\u0000' }) { "Invalid executable" }
        require((args + permissions.flatMap { it.args }).none { '\u0000' in it }) { "Arguments cannot contain NUL" }
        require(args.count { it == PROMPT_PLACEHOLDER } <= 1) { "Use {prompt} at most once" }
        require(versionArg == null || versionArg.matches(Regex("-[A-Za-z-]+"))) { "Invalid version argument" }
        require(extraEnv.all { it.matches(ENV_NAME) }) { "Invalid variable name" }
        require(permissions.map { it.name }.distinct().size == permissions.size) { "Permission names must differ" }
    }

    companion object {
        const val PROMPT_PLACEHOLDER = "{prompt}"
        const val PRESET_PREFIX = "preset:"
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Arguments that hand an unattended agent the whole machine; the approval card warns about them. */
        val DANGEROUS_ARGS = setOf(
            "bypassPermissions", "--dangerously-skip-permissions", "--dangerously-bypass-approvals-and-sandbox",
            "danger-full-access", "--yolo", "yolo", "-y",
        )
    }
}

/** Built-in presets. Flags verified in P1 except Gemini (see docs/REMOTE_SCHEDULED_TASKS_PLAN.md). */
object AgentToolPresets {
    const val READ_ONLY = "read_only"
    const val WORKSPACE_WRITE = "workspace_write"

    val CLAUDE_CODE = AgentToolDefinition(
        id = "preset:claude-code",
        name = "Claude Code",
        executable = "claude",
        args = listOf("-p", "--output-format", "json", "--no-session-persistence"),
        permissions = listOf(
            AgentToolDefinition.Permission(READ_ONLY, listOf("--permission-mode", "dontAsk")),
            AgentToolDefinition.Permission(WORKSPACE_WRITE, listOf("--permission-mode", "acceptEdits"), writes = true),
        ),
        extraction = ResultExtraction.JsonField("result", errorField = "result"),
        versionArg = "--version",
    )

    val CODEX = AgentToolDefinition(
        id = "preset:codex",
        name = "Codex CLI",
        executable = "codex",
        args = listOf("exec", "--skip-git-repo-check", "--json"),
        permissions = listOf(
            AgentToolDefinition.Permission(READ_ONLY, listOf("-s", "read-only", "-")),
            AgentToolDefinition.Permission(WORKSPACE_WRITE, listOf("-s", "workspace-write", "-"), writes = true),
        ),
        // Success events are unverified: P1 only saw the failure path (expired subscription).
        extraction = ResultExtraction.JsonLines(
            match = mapOf("type" to "item.completed", "item.type" to "agent_message"), field = "item.text",
            errorMatch = mapOf("type" to "turn.failed"), errorField = "error.message",
        ),
        versionArg = "--version",
    )

    val GEMINI = AgentToolDefinition(
        id = "preset:gemini",
        name = "Gemini CLI",
        executable = "gemini",
        args = listOf("-p", "--output-format", "json"),
        permissions = listOf(
            AgentToolDefinition.Permission(READ_ONLY, emptyList()),
            AgentToolDefinition.Permission(WORKSPACE_WRITE, listOf("--approval-mode", "auto_edit"), writes = true),
        ),
        extraction = ResultExtraction.JsonField("response"),
        versionArg = "--version",
    )

    val ALL = listOf(CLAUDE_CODE, CODEX, GEMINI)
}

/** `task probe`. */
@Serializable
data class RemoteTaskProbe(
    val scheduler: String,
    val linger: Boolean? = null,
    @SerialName("login_required") val loginRequired: Boolean = false,
    val timezone: String? = null,
    @SerialName("utc_offset") val utcOffset: String = "+0000",
) {
    val available: Boolean get() = scheduler != "none"
}

/** `task which`. */
@Serializable
data class RemoteExecutable(
    val path: String? = null,
    @SerialName("path_env") val pathEnv: String? = null,
    val version: String? = null,
)

/** One line of `runs/<run_id>.json`, plus the excerpt `task sync` attaches. */
@Serializable
data class RemoteTaskRunRecord(
    @SerialName("run_id") val runId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    val status: String,
    @SerialName("log_bytes") val logBytes: Long = 0,
    val truncated: Boolean = false,
    @SerialName("excerpt_b64") val excerptBase64: String? = null,
)

@Serializable
data class RemoteTaskSync(val runs: List<RemoteTaskRunRecord> = emptyList(), val cursor: String? = null)

/** An entry of `task list`. */
@Serializable
data class RemoteTaskListing(
    val id: String,
    val enabled: Boolean,
    @SerialName("timer_ok") val timerOk: Boolean,
    val backend: String? = null,
    val schedule: String? = null,
    val timeout: Int? = null,
    @SerialName("meta_b64") val metaBase64: String,
    @SerialName("last_run") val lastRun: RemoteTaskRunRecord? = null,
)

@Serializable
data class RemoteTaskList(val scheduler: String, val tasks: List<RemoteTaskListing> = emptyList())
