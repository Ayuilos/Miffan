package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Replaces the former "one linear slot with message alternatives" model with a
 * real parent/child message tree. The two schemas do not have equivalent branch
 * semantics, so old conversations and their message favorites are intentionally
 * removed instead of being migrated into a subtly corrupted tree.
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM favorites WHERE type IN ('node', 'message')")
        db.execSQL("DELETE FROM ConversationEntity")
        db.execSQL("DROP TABLE IF EXISTS message_node")
        db.execSQL("DROP TABLE IF EXISTS message_fts")

        db.execSQL(
            "ALTER TABLE ConversationEntity " +
                "ADD COLUMN selected_root_id TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_node (
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_message_node_conversation_id " +
                "ON message_node(conversation_id)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_message_node_conversation_id_parent_id " +
                "ON message_node(conversation_id, parent_id)"
        )
    }
}
