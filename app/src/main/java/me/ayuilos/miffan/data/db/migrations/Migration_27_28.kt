package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Connection identity changes invalidate approvals independently of display-name edits. */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN connection_revision TEXT NOT NULL DEFAULT 'legacy'")
    }
}
