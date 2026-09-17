package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds reusable SSH key metadata; private material remains outside Room and Android backup. */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_hosts ADD COLUMN ssh_key_id TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_hosts_ssh_key_id ON remote_hosts(ssh_key_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ssh_keys (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                algorithm TEXT NOT NULL,
                public_key TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ssh_keys_name ON ssh_keys(name)")
    }
}
