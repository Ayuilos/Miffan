package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.RemoteTaskEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskHostEntity
import me.ayuilos.miffan.data.db.entity.RemoteTaskRunEntity

@Dao
interface RemoteTaskDAO {
    @Query("SELECT * FROM remote_task WHERE host_id = :hostId ORDER BY task_id")
    fun tasksFlow(hostId: String): Flow<List<RemoteTaskEntity>>

    @Query("SELECT * FROM remote_task ORDER BY host_id, task_id")
    fun allTasksFlow(): Flow<List<RemoteTaskEntity>>

    @Query("SELECT * FROM remote_task WHERE host_id = :hostId")
    suspend fun tasks(hostId: String): List<RemoteTaskEntity>

    @Query("SELECT DISTINCT host_id FROM remote_task")
    suspend fun allTaskHostIds(): List<String>

    @Query("SELECT * FROM remote_task WHERE host_id = :hostId AND task_id = :taskId")
    suspend fun task(hostId: String, taskId: String): RemoteTaskEntity?

    @Upsert
    suspend fun upsertTask(task: RemoteTaskEntity)

    @Query("DELETE FROM remote_task WHERE host_id = :hostId AND task_id = :taskId")
    suspend fun deleteTask(hostId: String, taskId: String)

    /** Replaces the cached list with what the computer reported. */
    @Transaction
    suspend fun replaceTasks(hostId: String, tasks: List<RemoteTaskEntity>) {
        deleteTasks(hostId)
        tasks.forEach { upsertTask(it) }
    }

    @Query("DELETE FROM remote_task WHERE host_id = :hostId")
    suspend fun deleteTasks(hostId: String)

    @Query("SELECT * FROM remote_task_host WHERE host_id = :hostId")
    fun hostFlow(hostId: String): Flow<RemoteTaskHostEntity?>

    @Query("SELECT * FROM remote_task_host WHERE host_id = :hostId")
    suspend fun host(hostId: String): RemoteTaskHostEntity?

    @Upsert
    suspend fun upsertHost(host: RemoteTaskHostEntity)

    @Query("UPDATE remote_task_host SET cursor = :cursor, last_synced_at = :at, last_sync_error = NULL WHERE host_id = :hostId")
    suspend fun markSynced(hostId: String, cursor: String?, at: Long)

    @Query("UPDATE remote_task_host SET last_sync_error = :error WHERE host_id = :hostId")
    suspend fun markSyncFailed(hostId: String, error: String)

    /** Known runs are kept as they are, so a run is delivered once even when a sync overlaps. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRuns(runs: List<RemoteTaskRunEntity>): List<Long>

    @Query("SELECT * FROM remote_task_run WHERE host_id = :hostId AND task_id = :taskId ORDER BY run_id DESC LIMIT :limit")
    fun runsFlow(hostId: String, taskId: String, limit: Int = 30): Flow<List<RemoteTaskRunEntity>>

    @Query("SELECT * FROM remote_task_run WHERE delivered_at IS NULL ORDER BY run_id")
    suspend fun undeliveredRuns(): List<RemoteTaskRunEntity>

    @Query("UPDATE remote_task_run SET delivered_at = :at WHERE host_id = :hostId AND run_id IN (:runIds)")
    suspend fun markDelivered(hostId: String, runIds: List<String>, at: Long)
}
