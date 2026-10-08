package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS audit_event (
            id TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL,
            assistant_id TEXT, conversation_id TEXT, message_id TEXT, tool_call_id TEXT,
            host_id TEXT, host_name TEXT, tool_name TEXT, summary TEXT NOT NULL,
            decision TEXT, via TEXT, requested_at INTEGER
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audit_event_kind_tool_call_id ON audit_event (kind, tool_call_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_event_assistant_id_at ON audit_event (assistant_id, at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_event_host_id_at ON audit_event (host_id, at)")
    }
}
