package me.ayuilos.miffan.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Shareable public metadata. Private key material is kept in Keystore-encrypted no-backup storage. */
@Entity(tableName = "ssh_keys", indices = [Index(value = ["name"], unique = true)])
data class SshKeyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("algorithm") val algorithm: String,
    @ColumnInfo("public_key") val publicKey: String,
    @ColumnInfo("fingerprint") val fingerprint: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)
