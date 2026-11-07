package me.ayuilos.miffan.data.db.fts

import android.util.Log
import me.ayuilos.miffan.data.ai.AgentAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
    val chatId: String? = null,
)

enum class MessageSearchSort(val orderBy: String) {
    // update_at is the conversation's own (see search): the index rows keep only what they were saved with.
    RELEVANCE("rank, c.update_at DESC"),
    NEWEST_FIRST("c.update_at DESC, rank"),
    OLDEST_FIRST("c.update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase, private val imMode: () -> Boolean = { false }) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * Brings the index of [conversation] up to date by touching only the nodes that were added, edited
     * or removed since the last call. Rows are compared by node id, message id and text, so the index
     * heals itself even if an earlier save never reached it.
     */
    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        val desired = conversation.messageNodes.mapNotNull { node ->
            val text = node.message.extractFtsText()
            if (text.isBlank()) null else FtsRow(node.id.toString(), node.message.id.toString(), text)
        }
        db.beginTransaction()
        try {
            val existing = db.query(
                "SELECT rowid, node_id, message_id, text FROM message_fts WHERE conversation_id = ?",
                arrayOf(conversationId)
            ).use {
                buildList {
                    while (it.moveToNext()) {
                        add(FtsStoredRow(it.getLong(0), FtsRow(it.getString(1), it.getString(2), it.getString(3))))
                    }
                }
            }
            val plan = planFtsUpdate(existing, desired)
            plan.deleteRowIds.forEach { db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(it)) }
            plan.inserts.forEach { row ->
                db.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        row.text,
                        row.nodeId,
                        row.messageId,
                        conversationId,
                        conversation.title,
                        conversation.updateAt.toEpochMilli().toString(),
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // A null access preserves the owner UI/web global search.
        access: AgentAccess? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val matchQuery = buildFtsMatchQuery(keyword, ::simpleQuery) ?: return@withContext results
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, c.title, c.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet,
                   c.chat_id
            FROM message_fts
            JOIN conversationentity c ON c.id = message_fts.conversation_id
            WHERE message_fts.text MATCH ?
              AND ((? IS NULL AND c.chat_id IS NULL)
                  OR (c.assistant_id = ? AND (
                    (? = 1 AND c.chat_id IS NULL)
                    OR (? = 1 AND c.chat_id IS NOT NULL AND EXISTS (
                      SELECT 1 FROM partner_chat_member member
                      WHERE member.chat_id = c.chat_id AND member.assistant_id = ?
                    ))
                  )))
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf<Any?>(matchQuery, access?.callerId?.toString(), access?.callerId?.toString(),
                if (access?.ownerInitiated == true) 1 else 0, if (imMode()) 1 else 0, access?.callerId?.toString())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                        chatId = if (it.isNull(6)) null else it.getString(6),
                    )
                )
            }
        }
        results
    }

    // simple 扩展生成的查询，带拼音展开和前缀匹配
    private fun simpleQuery(text: String): String =
        db.query(
            "SELECT simple_query(?)",
            arrayOf(text)
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
}

private val QUERY_RUN = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]+")

// 中文片段各字之间最多允许夹几个额外的字（如标点、“的”），再大就容易把散落各处的字也算作命中
private const val CJK_NEAR_SLACK = 2

/**
 * 把搜索词拼成 FTS5 查询，各段之间用 AND 连接：
 * 英文和数字交给 [asciiQuery]（simple_query，支持拼音和前缀匹配）；
 * 索引按单字切分中文，所以中文等其他字符的连续片段拆成单字，用 NEAR 要求它们挨得足够近，
 * 效果接近按词匹配，又不需要 jieba 词典。
 * 没有可搜索的内容时返回 null。
 */
internal fun buildFtsMatchQuery(keyword: String, asciiQuery: (String) -> String): String? {
    val terms = QUERY_RUN.findAll(keyword).mapNotNull { match ->
        val text = match.value
        val term = if (text.first().isAsciiLetterOrDigit()) asciiQuery(text) else nearQuery(text)
        term.takeIf { it.isNotBlank() }
    }.toList()
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
}

private fun nearQuery(text: String): String {
    val chars = text.codePoints().toArray().map { "\"${String(Character.toChars(it)).replace("\"", "\"\"")}\"" }
    if (chars.size == 1) return chars.single()
    return "NEAR(${chars.joinToString(" ")}, ${chars.size - 2 + CJK_NEAR_SLACK})"
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)

/** One searchable node as stored in the index. */
internal data class FtsRow(val nodeId: String, val messageId: String, val text: String)

internal data class FtsStoredRow(val rowId: Long, val row: FtsRow)

internal data class FtsPlan(val deleteRowIds: List<Long>, val inserts: List<FtsRow>)

/**
 * What to change so the rows of one conversation equal [desired]: stored rows whose node is gone or
 * whose message id or text differ are deleted, and nodes without an identical stored row are inserted.
 * Unchanged nodes cost nothing. [desired] keeps node order so inserts stay in node order.
 */
internal fun planFtsUpdate(existing: List<FtsStoredRow>, desired: List<FtsRow>): FtsPlan {
    val wanted = desired.associateBy { it.nodeId }
    val kept = HashSet<String>()
    val delete = ArrayList<Long>()
    existing.forEach { stored ->
        // A second stored row of the same node is a leftover; keep one identical row at most.
        if (wanted[stored.row.nodeId] == stored.row && kept.add(stored.row.nodeId)) return@forEach
        delete.add(stored.rowId)
    }
    return FtsPlan(delete, desired.filter { it.nodeId !in kept })
}
