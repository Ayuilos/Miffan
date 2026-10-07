package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Remote screen: per-host VNC endpoint and auth. The screen password lives outside Room. */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN screen_enabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN screen_endpoint TEXT NOT NULL DEFAULT 'tcp:5900'")
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN screen_auth TEXT NOT NULL DEFAULT 'none'")
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN screen_username TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN screen_platform TEXT NOT NULL DEFAULT ''")
    }
}
