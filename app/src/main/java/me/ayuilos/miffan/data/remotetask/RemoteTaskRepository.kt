package me.ayuilos.miffan.data.remotetask

import android.content.res.AssetManager
import java.io.Closeable
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.RemoteTaskDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteTaskEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity
import me.ayuilos.miffan.data.repository.RemoteMiffanHelper
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemotePlatform
import kotlin.uuid.Uuid

/** Every agent tool the user can schedule: built-in presets, then their own. */
fun Settings.allAgentTools(): List<AgentToolDefinition> = AgentToolPresets.ALL + agentTools

/** What to create or replace. [taskId] null makes a new task. */
data class RemoteTaskDraft(
    val taskId: String? = null,
    val name: String,
    val kind: TaskKind,
    val command: String? = null,
    val agentToolId: String? = null,
    val permission: String? = null,
    val prompt: String? = null,
    /** Absolute directory on the computer; null uses the computer workspace's root. */
    val cwd: String? = null,
    val schedule: TaskSchedule,
    val timeoutSeconds: Int,
    val notify: TaskNotify,
    val owner: RemoteTaskMeta.Owner? = null,
)

/** Everything an approval card shows for an agent task: the exact command line it will run. */
data class ResolvedAgentCommand(val tool: AgentToolDefinition, val executable: RemoteExecutable, val argv: List<String>?) {
    val found: Boolean get() = argv != null
    val grantsFullAccess: Boolean get() = argv?.let(RemoteTaskScript::grantsFullAccess) == true
}

/**
 * Scheduled tasks on the user's computers. The computer owns each task (remote-helper `task`); this
 * keeps a cache for display and pulls finished runs when asked (the app never polls in the
 * background, see docs/REMOTE_SCHEDULED_TASKS_PLAN.md).
 */
class RemoteTaskRepository(
    private val workspaces: WorkspaceRepository,
    private val workspaceDao: WorkspaceDAO,
    private val hostDao: RemoteHostDAO,
    private val dao: RemoteTaskDAO,
    private val settingsStore: SettingsStore,
    assets: AssetManager,
) {
    private val helper = RemoteMiffanHelper(assets)
    private val json = Json { ignoreUnknownKeys = true }
    private val syncLock = Mutex()

    fun tasksFlow(hostId: String): Flow<List<RemoteTaskEntity>> = dao.tasksFlow(hostId)
    fun allTasksFlow(): Flow<List<RemoteTaskEntity>> = dao.allTasksFlow()
    fun hostFlow(hostId: String): Flow<RemoteTaskHostEntity?> = dao.hostFlow(hostId)
    fun runsFlow(hostId: String, taskId: String): Flow<List<RemoteTaskRunEntity>> = dao.runsFlow(hostId, taskId)
    fun latestRunsFlow(hostId: String): Flow<List<RemoteTaskRunEntity>> = dao.latestRunsFlow(hostId)

    suspend fun cachedTasks(hostId: String): List<RemoteTaskEntity> = dao.tasks(hostId)

    /** True when timers now keep running after logout; false means an administrator must do it. */
    suspend fun enableLinger(hostId: String): Boolean {
        val done = withClient(hostId) { it.enableLinger() }
        if (done) runCatching { refresh(hostId) }
        return done
    }

    /** The computer's IANA zone from the last probe, if any. */
    suspend fun timezone(hostId: String): String? = dao.host(hostId)?.timezone

    /** The run is already shown somewhere (e.g. a test run answered in the tool result). */
    suspend fun markDelivered(hostId: String, runIds: List<String>) {
        if (runIds.isNotEmpty()) dao.markDelivered(hostId, runIds, System.currentTimeMillis())
    }

    /** Windows computers have no scheduled tasks yet; the UI explains instead of offering them. */
    suspend fun isSupported(hostId: String): Boolean =
        hostDao.getById(hostId)?.let { RemotePlatform.parse(it.platform) != RemotePlatform.WINDOWS } == true

    /** This phone's Miffan key for the computer, as tasks record it; null when it signs in another way. */
    suspend fun keyFingerprint(hostId: String): String? =
        hostDao.getById(hostId)?.sshKeyId?.let { workspaces.getSshKeyById(it) }?.publicKey?.let(::sshKeyFingerprint)

    /** Whether forgetting Miffan with the key [fingerprint] removes [task] (see [RemoteTaskMeta.sshKey]). */
    fun removedWithKey(task: RemoteTaskEntity, fingerprint: String): Boolean =
        decodeMeta(task)?.sshKey.let { it == null || it == fingerprint }

    fun decodeMeta(task: RemoteTaskEntity): RemoteTaskMeta? =
        task.metaJson?.let { runCatching { json.decodeFromString(RemoteTaskMeta.serializer(), it) }.getOrNull() }

    /** Probes the scheduler and refreshes the task list. */
    suspend fun refresh(hostId: String): RemoteTaskProbe = withClient(hostId) { client ->
        val probe = client.probe()
        val list = client.list()
        probe to list
    }.let { (probe, list) ->
        saveProbe(hostId, probe)
        saveList(hostId, list)
        probe
    }

    /** Where [tool]'s executable is on the computer, and its version. */
    suspend fun resolveExecutable(hostId: String, tool: AgentToolDefinition): RemoteExecutable =
        withClient(hostId) { it.which(tool.executable, tool.versionArg) }

    /** Finds an agent tool's executable on the computer and builds its command line. */
    suspend fun resolveAgent(hostId: String, toolId: String, permission: String?, prompt: String): ResolvedAgentCommand {
        val tool = requireNotNull(settingsStore.settingsFlow.value.allAgentTools().find { it.id == toolId }) { "Unknown agent tool" }
        val executable = withClient(hostId) { it.which(tool.executable, tool.versionArg) }
        val argv = executable.path?.let { RemoteTaskScript.agentArgv(tool, permission, it, prompt) }
        return ResolvedAgentCommand(tool, executable, argv)
    }

    /** The draft that saves [task] again as it is, e.g. to pick up an edited agent tool. */
    fun draftOf(task: RemoteTaskEntity): RemoteTaskDraft? {
        val meta = decodeMeta(task) ?: return null
        val schedule = TaskSchedule.parseHelper(task.schedule) ?: return null
        return RemoteTaskDraft(
            taskId = task.taskId, name = meta.name, kind = meta.kind, command = meta.command,
            agentToolId = meta.agent?.toolId, permission = meta.agent?.permission, prompt = meta.prompt,
            cwd = meta.cwd, schedule = schedule, timeoutSeconds = task.timeoutSeconds, notify = meta.notify, owner = meta.owner,
        )
    }

    /** Cached tasks on any computer that run [toolId], as (host, task). */
    suspend fun tasksUsing(toolId: String): List<RemoteTaskEntity> =
        dao.allTasks().filter { decodeMeta(it)?.agent?.toolId == toolId }

    /**
     * Saves every task that runs [toolId] again so it uses the tool's current command line. Returns
     * the names that could not be updated (an unreachable computer, a missing executable) with why.
     */
    suspend fun retarget(toolId: String): Map<String, String> = buildMap {
        for (task in tasksUsing(toolId)) {
            val draft = draftOf(task)
            val name = draft?.name ?: task.taskId
            if (draft == null) { put(name, "unreadable task"); continue }
            runCatching { save(task.hostId, draft) }.onFailure { put(name, it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Creates or replaces a task, then refreshes the cache. Returns its id. */
    suspend fun save(hostId: String, draft: RemoteTaskDraft): String {
        draft.schedule.validate()
        require(draft.name.isNotBlank()) { "Name is empty" }
        val taskId = draft.taskId ?: Uuid.random().toString()
        val cwd = draft.cwd ?: computerRoot(hostId, draft.owner)
        val tool = draft.agentToolId?.let { id ->
            requireNotNull(settingsStore.settingsFlow.value.allAgentTools().find { it.id == id }) { "Unknown agent tool" }
        }
        val stored = draft.taskId?.let { dao.task(hostId, it) }?.let(::decodeMeta)
        val created = stored?.createdAt ?: System.currentTimeMillis()
        // A task stays with the phone that made it, even when another phone edits it.
        val sshKey = stored?.sshKey ?: keyFingerprint(hostId)
        val list = withClient(hostId) { client ->
            val (meta, rendered) = when (draft.kind) {
                TaskKind.COMMAND -> {
                    val command = requireNotNull(draft.command) { "Command is empty" }
                    val meta = RemoteTaskMeta(name = draft.name, kind = draft.kind, command = command, cwd = cwd,
                        notify = draft.notify, owner = draft.owner, sshKey = sshKey, createdAt = created)
                    meta to RemoteTaskScript.renderCommand(command, cwd, fallbackPath = null, env = meta.env)
                }
                TaskKind.AGENT -> {
                    requireNotNull(tool) { "Choose an agent tool" }
                    val prompt = requireNotNull(draft.prompt) { "Prompt is empty" }
                    val executable = client.which(tool.executable, null)
                    val path = executable.path ?: throw RemoteTaskCommandException("agent_missing:${tool.executable}")
                    val meta = RemoteTaskMeta(name = draft.name, kind = draft.kind, cwd = cwd, prompt = prompt,
                        agent = RemoteTaskMeta.AgentRef(tool.id, tool.name, draft.permission, tool.extraction),
                        env = (DEFAULT_TASK_ENV + tool.extraEnv).distinct(),
                        notify = draft.notify, owner = draft.owner, sshKey = sshKey, createdAt = created)
                    meta to RemoteTaskScript.renderAgent(tool, draft.permission, prompt, cwd, path, executable.pathEnv)
                }
            }
            client.commit(taskId, meta, rendered, draft.schedule, draft.timeoutSeconds)
            client.list()
        }
        saveList(hostId, list)
        return taskId
    }

    suspend fun setEnabled(hostId: String, taskId: String, enabled: Boolean) {
        saveList(hostId, withClient(hostId) { it.setEnabled(taskId, enabled); it.list() })
    }

    suspend fun runNow(hostId: String, taskId: String) = withClient(hostId) { it.runNow(taskId) }

    suspend fun remove(hostId: String, taskId: String) {
        withClient(hostId) { it.remove(taskId) }
        dao.deleteTask(hostId, taskId)
    }

    suspend fun log(hostId: String, taskId: String, runId: String): String = withClient(hostId) { it.log(taskId, runId) }

    /**
     * Pulls finished runs from one computer. Starts two minutes before the stored cursor and relies
     * on the run id key to drop duplicates: a run written while the last sync was listing could
     * otherwise be skipped. Returns the newly stored runs.
     */
    suspend fun sync(hostId: String): List<RemoteTaskRunEntity> = syncLock.withLock {
        val state = dao.host(hostId)
        val result = runCatching {
            withClient(hostId) { client -> client.probe() to (client.sync(state?.cursor?.let(::rewind)) to client.list()) }
        }
        val (probe, rest) = result.getOrElse { error ->
            if (state != null) dao.markSyncFailed(hostId, error.message ?: error.javaClass.simpleName)
            throw error
        }
        val (sync, list) = rest
        saveProbe(hostId, probe)
        saveList(hostId, list)
        val tasks = dao.tasks(hostId).associateBy { it.taskId }
        val tools = settingsStore.settingsFlow.value.allAgentTools().associateBy { it.id }
        val now = System.currentTimeMillis()
        val runs = sync.runs.map { record ->
            val excerpt = RemoteTaskClient.excerpt(record)
            val meta = tasks[record.taskId]?.let(::decodeMeta)
            val extraction = meta?.agent?.let { it.extraction ?: tools[it.toolId]?.extraction }
            val extracted = extraction?.let { RemoteTaskResults.extract(excerpt, it) }
            RemoteTaskRunEntity(
                hostId = hostId, runId = record.runId, taskId = record.taskId,
                startedAt = parseInstant(record.startedAt), endedAt = parseInstant(record.endedAt),
                exitCode = record.exitCode, status = record.status, logBytes = record.logBytes,
                truncated = record.truncated, excerpt = excerpt,
                resultText = extracted?.text, resultError = extracted?.error,
                syncedAt = now, deliveredAt = null,
            )
        }
        val inserted = dao.insertRuns(runs)
        val cursor = listOfNotNull(state?.cursor, sync.cursor).maxOrNull()
        dao.markSynced(hostId, cursor, now)
        runs.filterIndexed { index, _ -> inserted.getOrNull(index)?.let { it != -1L } == true }
    }

    /**
     * Syncs every supported computer that has tasks, skipping ones synced within [minIntervalMillis].
     * Failures are recorded per computer and never stop the others.
     */
    suspend fun syncAll(minIntervalMillis: Long = 5 * 60_000L): List<RemoteTaskRunEntity> {
        val now = System.currentTimeMillis()
        val hostIds = dao.allTaskHostIds()
        return hostIds.flatMap { hostId ->
            val last = dao.host(hostId)?.lastSyncedAt
            if (last != null && now - last < minIntervalMillis || !isSupported(hostId)) emptyList()
            else runCatching { sync(hostId) }.getOrDefault(emptyList())
        }
    }

    /**
     * The folder a task runs in when none is given: the owner partner's workspace on this computer,
     * else its oldest one. A remote workspace's `root` is a local id; its folder there is `remotePath`.
     */
    private suspend fun computerRoot(hostId: String, owner: RemoteTaskMeta.Owner?): String {
        val workspaces = workspaceDao.getByRemoteHostId(hostId)
        val partnerWorkspace = owner?.let { o ->
            settingsStore.settingsFlow.value.assistants.find { it.id == o.assistantId }?.workspaceId?.toString()
        }
        val workspace = workspaces.find { it.id == partnerWorkspace } ?: workspaces.minByOrNull { it.createdAt }
        return requireNotNull(workspace?.remotePath?.takeIf { it.startsWith("/") }) { "The computer has no workspace folder" }
    }

    private suspend fun <T> withClient(hostId: String, block: (RemoteTaskClient) -> T): T {
        if (!isSupported(hostId)) throw RemoteTasksUnsupportedException()
        val workspaceId = workspaceDao.getByRemoteHostId(hostId).minByOrNull { it.createdAt }?.id
            ?: throw IllegalStateException("The computer has no workspace")
        return withContext(Dispatchers.IO) {
            var value: Result<T>? = null
            workspaces.openLeasedRemote(workspaceId) { ssh ->
                value = runCatching {
                    helper.ensureInstalled(ssh)
                    block(RemoteTaskClient(ssh))
                }
                Closeable {}
            }.close()
            requireNotNull(value).getOrThrow()
        }
    }

    private suspend fun saveProbe(hostId: String, probe: RemoteTaskProbe) {
        val current = dao.host(hostId)
        dao.upsertHost(RemoteTaskHostEntity(
            hostId = hostId, scheduler = probe.scheduler, linger = probe.linger, loginRequired = probe.loginRequired,
            timezone = probe.timezone, utcOffset = probe.utcOffset, cursor = current?.cursor,
            lastSyncedAt = current?.lastSyncedAt, lastSyncError = current?.lastSyncError,
            probedAt = System.currentTimeMillis(),
        ))
    }

    private suspend fun saveList(hostId: String, list: RemoteTaskList) {
        val now = System.currentTimeMillis()
        dao.replaceTasks(hostId, list.tasks.map { listing ->
            RemoteTaskEntity(
                hostId = hostId, taskId = listing.id,
                metaJson = RemoteTaskClient.decodeMeta(listing)?.let { json.encodeToString(RemoteTaskMeta.serializer(), it) },
                schedule = listing.schedule.orEmpty(), enabled = listing.enabled, timerOk = listing.timerOk,
                backend = listing.backend.orEmpty(), timeoutSeconds = listing.timeout ?: 600, updatedAt = now,
            )
        })
    }

    companion object {
        /** Run ids are `YYYYMMDDTHHMMSSZ-pid`; returns the id two minutes earlier with pid 0. */
        internal fun rewind(cursor: String): String {
            val stamp = cursor.substringBefore('-')
            val instant = runCatching {
                java.time.LocalDateTime.parse(stamp, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                    .toInstant(java.time.ZoneOffset.UTC)
            }.getOrNull() ?: return cursor
            return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(instant.minusSeconds(120)) + "-0"
        }

        private fun parseInstant(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }
}
