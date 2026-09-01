package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.ayuilos.miffan.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Query("SELECT id FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT id, revision FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getNodeRevisionsOfConversation(conversationId: String): List<MessageNodeRevision>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM message_node WHERE id IN (:nodeIds)")
    suspend fun deleteByIds(nodeIds: List<String>)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

data class MessageNodeRevision(val id: String, val revision: Long)

// Each row stores one message, so stats avoid expanding a JSON array.
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(message, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(message, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(message, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(message, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node " +
                "WHERE json_extract(message, '$.role') = 'user' " +
                "AND json_extract(message, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )
