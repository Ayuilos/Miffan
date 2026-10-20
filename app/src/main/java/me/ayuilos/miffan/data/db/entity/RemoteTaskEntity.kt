package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The app's copy of a scheduled task on a computer, refreshed from `task list`. The computer is the
 * source of truth; this lets the app show tasks while the computer is unreachable.
 */
@Entity(
    tableName = "remote_task",
    primaryKeys = ["host_id", "task_id"],
    foreignKeys = [ForeignKey(
        entity = RemoteHostEntity::class, parentColumns = ["id"], childColumns = ["host_id"], onDelete = ForeignKey.CASCADE,
    )],
)
data class RemoteTaskEntity(
    @ColumnInfo("host_id") val hostId: String,
    @ColumnInfo("task_id") val taskId: String,
    /** [me.ayuilos.miffan.data.remotetask.RemoteTaskMeta] as JSON; null when the computer's copy is unreadable. */
    @ColumnInfo("meta_json") val metaJson: String?,
    /** The helper's schedule line, e.g. `daily 09:00`. */
    @ColumnInfo("schedule") val schedule: String,
    @ColumnInfo("enabled") val enabled: Boolean,
    /** The system timer is really registered; false for an enabled task means it will not run. */
    @ColumnInfo("timer_ok") val timerOk: Boolean,
    @ColumnInfo("backend") val backend: String,
    @ColumnInfo("timeout_seconds") val timeoutSeconds: Int,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

/** Per computer: what `task probe` said and how far results have been pulled. */
@Entity(
    tableName = "remote_task_host",
    primaryKeys = ["host_id"],
    foreignKeys = [ForeignKey(
        entity = RemoteHostEntity::class, parentColumns = ["id"], childColumns = ["host_id"], onDelete = ForeignKey.CASCADE,
    )],
)
data class RemoteTaskHostEntity(
    @ColumnInfo("host_id") val hostId: String,
    /** systemd, launchd, cron or none. */
    @ColumnInfo("scheduler") val scheduler: String,
    @ColumnInfo("linger") val linger: Boolean?,
    @ColumnInfo("login_required") val loginRequired: Boolean,
    @ColumnInfo("timezone") val timezone: String?,
    @ColumnInfo("utc_offset") val utcOffset: String,
    /** The largest run id already pulled; the next sync starts a little before it. */
    @ColumnInfo("cursor") val cursor: String?,
    @ColumnInfo("last_synced_at") val lastSyncedAt: Long?,
    /** Why the last attempt failed, or null after a success. */
    @ColumnInfo("last_sync_error") val lastSyncError: String?,
    @ColumnInfo("probed_at") val probedAt: Long,
)

/** A finished run pulled from the computer. */
@Entity(
    tableName = "remote_task_run",
    primaryKeys = ["host_id", "run_id"],
    indices = [Index(value = ["host_id", "task_id"]), Index(value = ["delivered_at"])],
    foreignKeys = [ForeignKey(
        entity = RemoteHostEntity::class, parentColumns = ["id"], childColumns = ["host_id"], onDelete = ForeignKey.CASCADE,
    )],
)
data class RemoteTaskRunEntity(
    @ColumnInfo("host_id") val hostId: String,
    @ColumnInfo("run_id") val runId: String,
    @ColumnInfo("task_id") val taskId: String,
    @ColumnInfo("started_at") val startedAt: Long,
    @ColumnInfo("ended_at") val endedAt: Long,
    @ColumnInfo("exit_code") val exitCode: Int?,
    /** ok, failed, timeout or skipped_overlap. */
    @ColumnInfo("status") val status: String,
    @ColumnInfo("log_bytes") val logBytes: Long,
    @ColumnInfo("truncated") val truncated: Boolean,
    /** The end of the output (at most 16 KiB). */
    @ColumnInfo("excerpt") val excerpt: String,
    @ColumnInfo("result_text") val resultText: String?,
    @ColumnInfo("result_error") val resultError: String?,
    @ColumnInfo("synced_at") val syncedAt: Long,
    /** When the run was handed to its partner's chat; null until then. */
    @ColumnInfo("delivered_at") val deliveredAt: Long?,
)
