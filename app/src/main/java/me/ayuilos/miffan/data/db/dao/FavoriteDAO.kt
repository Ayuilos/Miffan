package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.FavoriteEntity

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY created_at DESC")
    fun listByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type AND NOT EXISTS (SELECT 1 FROM conversationentity c WHERE c.chat_id IS NOT NULL AND favorites.ref_key LIKE 'node:' || c.id || ':%') ORDER BY created_at DESC")
    fun listVisibleByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT ref_key FROM favorites WHERE type = :type")
    suspend fun getRefKeysByType(type: String): List<String>

    /**
     * Node ids of favorites whose ref_key starts with [prefix]. The caller passes the exclusive upper
     * bound [prefixEnd] (`prefix` with its last char + 1) so the unique ref_key index serves a range scan; LIKE can't.
     */
    @Query("SELECT substr(ref_key, length(:prefix) + 1) FROM favorites WHERE ref_key >= :prefix AND ref_key < :prefixEnd")
    suspend fun getFavoriteNodeIdsByKeyRange(prefix: String, prefixEnd: String): List<String>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey LIMIT 1")
    suspend fun getByRefKey(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE ref_key = :refKey)")
    suspend fun existsByRefKey(refKey: String): Boolean

    @Query("DELETE FROM favorites WHERE ref_key = :refKey")
    suspend fun deleteByRefKey(refKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM favorites WHERE ref_key >= :prefix AND ref_key < :prefixEnd")
    suspend fun listByKeyRange(prefix: String, prefixEnd: String): List<FavoriteEntity>

    /** Range counterpart of [getFavoriteNodeIdsByKeyRange]: deletes every ref_key in [prefix, prefixEnd). */
    @Query("DELETE FROM favorites WHERE ref_key >= :prefix AND ref_key < :prefixEnd")
    suspend fun deleteByKeyRange(prefix: String, prefixEnd: String): Int
}

/** Node ids favorited within [conversationId]; ref keys look like `node:<conversationId>:<nodeId>`. */
suspend fun FavoriteDAO.getFavoriteNodeIdsOfConversation(conversationId: String): List<String> {
    val prefix = "node:$conversationId:"
    // ';' is the character after ':', so [prefix, prefixEnd) is exactly the keys starting with prefix.
    return getFavoriteNodeIdsByKeyRange(prefix, "node:$conversationId;")
}

/** Drops every node favorite of [conversationId]; the favorites table has no foreign key to conversations. */
suspend fun FavoriteDAO.deleteNodeFavoritesOfConversation(conversationId: String): Int =
    deleteByKeyRange("node:$conversationId:", "node:$conversationId;")
