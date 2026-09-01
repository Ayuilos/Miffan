package me.ayuilos.miffan.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.utils.JsonInstant

private const val TAG = "Migration_24_25"

/**
 * Replaces the former "one linear slot with message alternatives" model with a
 * real parent/child message tree.
 *
 * Legacy alternatives do not contain enough information to reconstruct their
 * downstream branch relationships. Preserve each conversation's currently
 * selected path instead: every old slot contributes its selected message and
 * those messages are linked in their original order. Unselected legacy
 * alternatives are intentionally not migrated.
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ConversationEntity " +
                "ADD COLUMN selected_root_id TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            """
            CREATE TABLE message_node_v25 (
                id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                node_index INTEGER NOT NULL,
                parent_id TEXT NOT NULL DEFAULT '',
                selected_child_id TEXT NOT NULL DEFAULT '',
                message TEXT NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id),
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        val conversationIds = buildList {
            db.query("SELECT id FROM ConversationEntity").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val hasMessageFts = db.hasTable("message_fts")

        conversationIds.forEach { conversationId ->
            val selectedNodes = buildList {
                db.query(
                    """
                    SELECT id, messages, select_index
                    FROM message_node
                    WHERE conversation_id = ?
                    ORDER BY node_index ASC
                    """.trimIndent(),
                    arrayOf(conversationId),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val nodeId = cursor.getString(0)
                        val messagesJson = cursor.getString(1)
                        val selectIndex = cursor.getInt(2)
                        val selectedMessage = runCatching {
                            val messages = JsonInstant.parseToJsonElement(messagesJson) as? JsonArray
                                ?: return@runCatching null
                            messages.getOrNull(selectIndex) ?: messages.firstOrNull()
                        }.getOrElse { error ->
                            Log.w(TAG, "Skipping malformed legacy node $nodeId", error)
                            null
                        }

                        val selectedMessageId = (selectedMessage as? JsonObject)
                            ?.get("id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                        if (hasMessageFts) {
                            if (selectedMessageId == null) {
                                db.execSQL(
                                    "DELETE FROM message_fts WHERE conversation_id = ? AND node_id = ?",
                                    arrayOf(conversationId, nodeId),
                                )
                            } else {
                                db.execSQL(
                                    """
                                    DELETE FROM message_fts
                                    WHERE conversation_id = ? AND node_id = ? AND message_id <> ?
                                    """.trimIndent(),
                                    arrayOf(conversationId, nodeId, selectedMessageId),
                                )
                            }
                        }

                        if (selectedMessage != null) {
                            add(LegacySelectedNode(nodeId, JsonInstant.encodeToString(selectedMessage)))
                        }
                    }
                }
            }

            selectedNodes.forEachIndexed { index, node ->
                db.execSQL(
                    """
                    INSERT INTO message_node_v25 (
                        id, conversation_id, node_index, parent_id,
                        selected_child_id, message, revision
                    ) VALUES (?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    arrayOf(
                        node.id,
                        conversationId,
                        index,
                        selectedNodes.getOrNull(index - 1)?.id.orEmpty(),
                        selectedNodes.getOrNull(index + 1)?.id.orEmpty(),
                        node.messageJson,
                    ),
                )
            }

            db.execSQL(
                "UPDATE ConversationEntity SET selected_root_id = ? WHERE id = ?",
                arrayOf(selectedNodes.firstOrNull()?.id.orEmpty(), conversationId),
            )
        }

        db.execSQL("DROP TABLE message_node")
        db.execSQL("ALTER TABLE message_node_v25 RENAME TO message_node")
        db.execSQL(
            "CREATE INDEX index_message_node_conversation_id " +
                "ON message_node(conversation_id)"
        )
        db.execSQL(
            "CREATE INDEX index_message_node_conversation_id_parent_id " +
                "ON message_node(conversation_id, parent_id)"
        )
    }
}

private data class LegacySelectedNode(
    val id: String,
    val messageJson: String,
)

private fun SupportSQLiteDatabase.hasTable(tableName: String): Boolean = query(
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
    arrayOf(tableName),
).use { it.moveToFirst() }
