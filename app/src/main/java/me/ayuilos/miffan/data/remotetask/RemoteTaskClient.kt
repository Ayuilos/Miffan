package me.ayuilos.miffan.data.remotetask

import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession

/** Windows helpers have no `task` command yet; callers show their own explanation. */
class RemoteTasksUnsupportedException : IllegalStateException("Scheduled tasks are not supported on Windows computers yet")

/** The helper refused or failed; [detail] is its message, e.g. `login_required`. */
class RemoteTaskCommandException(val detail: String) : IllegalStateException(detail) {
    val loginRequired: Boolean get() = detail == "login_required"
}

/**
 * `miffan task` over one SSH session (remote-helper/PROTOCOL.md). The helper must already be
 * installed; [RemoteTaskRepository] does that before handing out a client.
 */
internal class RemoteTaskClient(private val ssh: RemoteWorkspaceSession) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (ssh.platform == RemotePlatform.WINDOWS) throw RemoteTasksUnsupportedException()
    }

    fun probe(): RemoteTaskProbe = json.decodeFromString(run("probe"))

    fun which(executable: String, versionArg: String?): RemoteExecutable =
        json.decodeFromString(run("which", executable, *listOfNotNull(versionArg).toTypedArray(), timeoutMillis = 40_000))

    /** Stages and commits; returns the timer backend. Refuses before staging anything invalid. */
    fun commit(taskId: String, meta: RemoteTaskMeta, rendered: RenderedTask, schedule: TaskSchedule, timeoutSeconds: Int): String {
        require(taskId.matches(TASK_ID)) { "Invalid task id" }
        require(timeoutSeconds in 60..86_400) { "Timeout must be 1 minute to 24 hours" }
        val args = schedule.helperArgs()
        stage(taskId, "meta", json.encodeToString(RemoteTaskMeta.serializer(), meta))
        stage(taskId, "run", rendered.run)
        stage(taskId, "prompt", rendered.prompt)
        stage(taskId, "env", rendered.env)
        val output = run("commit", taskId, timeoutSeconds.toString(), *args.toTypedArray(), timeoutMillis = 60_000)
        return json.decodeFromString<JsonObject>(output)["backend"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    fun list(): RemoteTaskList = json.decodeFromString(run("list", timeoutMillis = 30_000))

    fun setEnabled(taskId: String, enabled: Boolean) { run(if (enabled) "enable" else "disable", taskId) }

    fun runNow(taskId: String) { run("run", taskId) }

    fun remove(taskId: String) { run("remove", taskId) }

    fun removeAll() { run("remove", "--all") }

    fun sync(cursor: String?): RemoteTaskSync =
        json.decodeFromString(run("sync", *listOfNotNull(cursor).toTypedArray(), timeoutMillis = 60_000))

    fun log(taskId: String, runId: String): String {
        val result = ssh.execute("$HELPER task log ${quote(taskId)} ${quote(runId)}", timeoutMillis = 30_000, maxOutputBytes = 2 * 1024 * 1024)
        if (result.exitCode != 0 || result.timedOut) throw RemoteTaskCommandException(detail(result.stdout) ?: "Could not read the log")
        return result.stdout
    }

    private fun stage(taskId: String, part: String, content: String) {
        run("stage", taskId, part, stdin = content.toByteArray())
    }

    private fun run(vararg args: String, stdin: ByteArray? = null, timeoutMillis: Long = 20_000): String {
        val command = "$HELPER task " + args.joinToString(" ") { quote(it) }
        val result = ssh.execute(command, timeoutMillis = timeoutMillis, stdin = stdin?.let(::ByteArrayInputStream))
        val line = result.stdout.trim().lineSequence().lastOrNull().orEmpty()
        if (result.timedOut) throw RemoteTaskCommandException("The computer did not answer in time")
        val success = runCatching { json.decodeFromString<JsonObject>(line)["success"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
        if (result.exitCode != 0 || success == false) {
            throw RemoteTaskCommandException(detail(line) ?: result.stderr.trim().ifBlank { "Helper command failed" })
        }
        return line
    }

    private fun detail(line: String): String? =
        runCatching { json.decodeFromString<JsonObject>(line.trim().lineSequence().last())["detail"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    companion object {
        private const val HELPER = "\"\$HOME/.miffan/bin/miffan\""
        val TASK_ID = Regex("[A-Za-z0-9-]{1,64}")

        fun quote(text: String) = RemoteTaskScript.quote(text)

        fun decodeMeta(listing: RemoteTaskListing): RemoteTaskMeta? = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(
                RemoteTaskMeta.serializer(), Base64.decode(listing.metaBase64).decodeToString(),
            )
        }.getOrNull()

        fun excerpt(record: RemoteTaskRunRecord): String =
            record.excerptBase64?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }.orEmpty()
    }
}
