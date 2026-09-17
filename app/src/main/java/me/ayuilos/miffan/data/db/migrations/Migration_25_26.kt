package me.ayuilos.miffan.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing workspaces are local; remote host details and credentials are separate. */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workspaces ADD COLUMN kind TEXT NOT NULL DEFAULT 'LOCAL'")
        db.execSQL("ALTER TABLE workspaces ADD COLUMN remote_host_id TEXT")
        db.execSQL("ALTER TABLE workspaces ADD COLUMN remote_path TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS remote_hosts (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT NOT NULL,
                auth_type TEXT NOT NULL,
                trusted_host_key_sha256 TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_remote_hosts_name ON remote_hosts(name)")
    }
}
