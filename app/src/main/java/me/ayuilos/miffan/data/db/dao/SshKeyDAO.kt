package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.SshKeyEntity

@Dao
interface SshKeyDAO {
    @Query("SELECT * FROM ssh_keys ORDER BY name COLLATE NOCASE")
    fun listFlow(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: String): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(key: SshKeyEntity)

    @Update
    suspend fun update(key: SshKeyEntity): Int

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
