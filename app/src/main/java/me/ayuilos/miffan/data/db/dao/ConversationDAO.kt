package me.ayuilos.miffan.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' ORDER BY is_pinned DESC, update_at DESC")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE folder_id = :folderId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfFolderPaging(folderId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity ORDER BY update_at DESC LIMIT :limit")
    fun observeRecentConversations(limit: Int): Flow<List<LightConversationEntity>>

    /**
     * Change stamps of the newest segments of an assistant's IM thread. Reading message_node makes
     * Room re-emit on node-only saves; the stamp changes whenever a segment's visible content does.
     */
    @Query(
        "SELECT c.id AS id, c.title AS title, c.thread_summary AS threadSummary, c.thread_closed_at AS threadClosedAt, " +
            "c.selected_root_id AS selectedRootId, " +
            "(SELECT COUNT(*) FROM message_node n WHERE n.conversation_id = c.id) AS nodeCount, " +
            "(SELECT COALESCE(SUM(n.revision), 0) FROM message_node n WHERE n.conversation_id = c.id) AS revisionSum " +
            "FROM conversationentity c WHERE c.assistant_id = :assistantId ORDER BY c.update_at DESC LIMIT :limit"
    )
    fun observeThreadSegmentStamps(assistantId: String, limit: Int): Flow<List<ThreadSegmentStamp>>

    /** Summarized earlier segments of an assistant's thread, newest first. */
    @Query("SELECT id, title, thread_summary AS threadSummary, update_at AS updateAt FROM conversationentity WHERE assistant_id = :assistantId AND id != :excludeId AND thread_summary != '' ORDER BY update_at DESC LIMIT :limit")
    suspend fun getThreadDigests(assistantId: String, excludeId: String, limit: Int): List<ThreadSegmentDigest>

    // SQLite returns the bare columns of the row holding MAX(update_at) within each group.
    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, MAX(update_at) as updateAt, folder_id as folderId FROM conversationentity GROUP BY assistant_id ORDER BY updateAt DESC")
    fun observeLatestConversationOfEachAssistant(): Flow<List<LightConversationEntity>>

    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

/** Cheap fingerprint of a thread segment; equal stamps mean the loaded segment is current. */
data class ThreadSegmentStamp(
    val id: String,
    val title: String,
    val threadSummary: String,
    val threadClosedAt: Long,
    val selectedRootId: String,
    val nodeCount: Int,
    val revisionSum: Long,
)

data class ThreadSegmentDigest(
    val id: String,
    val title: String,
    val threadSummary: String,
    val updateAt: Long,
)
