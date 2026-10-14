package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN alternate_addresses TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN last_address TEXT")
    }
}
