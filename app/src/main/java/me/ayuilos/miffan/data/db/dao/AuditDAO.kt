package me.ayuilos.miffan.data.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.AuditEventEntity

internal const val AUDIT_TRIM_SQL = "DELETE FROM audit_event WHERE id NOT IN (SELECT id FROM audit_event ORDER BY at DESC, rowid DESC LIMIT 5000)"

@Dao
abstract class AuditDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(event: AuditEventEntity)

    @Query(AUDIT_TRIM_SQL)
    abstract suspend fun trim()

    @Transaction
    open suspend fun record(event: AuditEventEntity) {
        insertIgnore(event)
        trim()
    }

    @Query("SELECT * FROM audit_event WHERE (:assistantId IS NULL OR assistant_id = :assistantId OR (kind IN ('SCREEN_TAKEN_OVER', 'SCREEN_HANDED_BACK') AND host_id IN (:hostIds))) ORDER BY at DESC, rowid DESC LIMIT :limit")
    abstract fun observe(assistantId: String?, hostIds: Set<String>, limit: Int): Flow<List<AuditEventEntity>>

    @Query("DELETE FROM audit_event WHERE :assistantId IS NULL OR assistant_id = :assistantId")
    abstract suspend fun clear(assistantId: String?)
}
