package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity

@Dao
interface RemoteHostDAO {
    @Query("SELECT * FROM remote_hosts ORDER BY name COLLATE NOCASE")
    fun listFlow(): Flow<List<RemoteHostEntity>>

    @Query("SELECT * FROM remote_hosts WHERE id = :id")
    suspend fun getById(id: String): RemoteHostEntity?

    @Query("SELECT COUNT(*) FROM remote_hosts WHERE ssh_key_id = :sshKeyId")
    suspend fun countBySshKeyId(sshKeyId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(host: RemoteHostEntity)

    @Update
    suspend fun update(host: RemoteHostEntity): Int

    @Query("DELETE FROM remote_hosts WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
