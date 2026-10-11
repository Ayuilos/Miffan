package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE partner_chat ADD COLUMN host_id TEXT")
        db.execSQL("ALTER TABLE partner_chat_message ADD COLUMN mentions TEXT NOT NULL DEFAULT '[]'")
    }
}
