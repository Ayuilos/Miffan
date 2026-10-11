package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN chat_id TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS partner_chat (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS partner_chat_member (
            chat_id TEXT NOT NULL, assistant_id TEXT NOT NULL, joined_at INTEGER NOT NULL,
            perspective_segment_id TEXT, synced_message_id TEXT,
            PRIMARY KEY(chat_id, assistant_id),
            FOREIGN KEY(chat_id) REFERENCES partner_chat(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_partner_chat_member_assistant_id ON partner_chat_member(assistant_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS partner_chat_message (
            id TEXT NOT NULL PRIMARY KEY, chat_id TEXT NOT NULL, sender_id TEXT NOT NULL,
            text TEXT NOT NULL, created_at INTEGER NOT NULL, exchange_id TEXT, reply_to TEXT,
            chain TEXT NOT NULL DEFAULT '[]',
            FOREIGN KEY(chat_id) REFERENCES partner_chat(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_partner_chat_message_chat_id ON partner_chat_message(chat_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_partner_chat_message_exchange_id ON partner_chat_message(exchange_id)")
    }
}
