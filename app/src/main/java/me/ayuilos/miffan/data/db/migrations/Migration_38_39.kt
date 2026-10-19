package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN topic_start TEXT DEFAULT NULL")
    }
}
