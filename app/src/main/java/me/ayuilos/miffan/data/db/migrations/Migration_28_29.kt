package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** IM threads: segment summaries, explicit replies, memory sources and the revision history. */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN thread_summary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN thread_closed_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE message_node ADD COLUMN reply_to TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN source_conversation_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE MemoryEntity ADD COLUMN source_message_id TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS revision (
                id TEXT NOT NULL PRIMARY KEY,
                subject_type TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                parent_id TEXT NOT NULL DEFAULT '',
                author TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                snapshot TEXT NOT NULL,
                trigger_conversation_id TEXT NOT NULL DEFAULT '',
                trigger_message_id TEXT NOT NULL DEFAULT '',
                trigger_tool_call_id TEXT NOT NULL DEFAULT '',
                revert_of TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_revision_subject_type_subject_id_created_at " +
                "ON revision (subject_type, subject_id, created_at)"
        )
    }
}
