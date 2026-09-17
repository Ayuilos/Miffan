package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Public connection details only. Passwords and private keys live in [RemoteHostCredentialStore]. */
@Entity(tableName = "remote_hosts", indices = [
    Index(value = ["name"], unique = true),
    Index(value = ["ssh_key_id"]),
])
data class RemoteHostEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("host") val host: String,
    @ColumnInfo("port") val port: Int,
    @ColumnInfo("username") val username: String,
    @ColumnInfo("auth_type") val authType: String,
    @ColumnInfo("connection_revision", defaultValue = "'legacy'")
    val connectionRevision: String = "legacy",
    @ColumnInfo("ssh_key_id") val sshKeyId: String? = null,
    @ColumnInfo("trusted_host_key_sha256") val trustedHostKeySha256: String?,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
