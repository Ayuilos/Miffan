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
    @ColumnInfo("screen_enabled", defaultValue = "0") val screenEnabled: Boolean = false,
    /** `helper`, `tcp:<port>` on the remote loopback, or `unix:<absolute path>` (see [RemoteScreenEndpoint]). */
    @ColumnInfo("screen_endpoint", defaultValue = "'tcp:5900'") val screenEndpoint: String = "tcp:5900",
    /** One of [RemoteScreenAuth] names, lower case. */
    @ColumnInfo("screen_auth", defaultValue = "'none'") val screenAuth: String = "none",
    /** Account for macOS screen sharing; blank means the SSH username. */
    @ColumnInfo("screen_username", defaultValue = "''") val screenUsername: String = "",
    /** `macos`, `linux`, or blank until detected over SSH. */
    @ColumnInfo("screen_platform", defaultValue = "''") val screenPlatform: String = "",
    @ColumnInfo("screen_protocol", defaultValue = "'auto'") val screenProtocol: String = "auto",
    @ColumnInfo("rdp_username", defaultValue = "''") val rdpUsername: String = "",
    @ColumnInfo("rdp_certificate_sha256") val rdpCertificateSha256: String? = null,
    @ColumnInfo("stream_enabled", defaultValue = "0") val streamEnabled: Boolean = false,
    @ColumnInfo("stream_certificate_sha256") val streamCertificateSha256: String? = null,
)

enum class RemoteScreenAuth { NONE, VNC_PASSWORD, MACOS_ACCOUNT;
    val storageName: String get() = name.lowercase()
    companion object {
        fun parse(value: String): RemoteScreenAuth = entries.firstOrNull { it.storageName == value } ?: NONE
    }
}

sealed interface RemoteScreenEndpoint {
    data class Tcp(val port: Int) : RemoteScreenEndpoint
    data class Unix(val path: String) : RemoteScreenEndpoint

    /** The Miffan helper starts a per-user desktop server and reports where it listens. */
    data object Helper : RemoteScreenEndpoint

    val storageValue: String get() = when (this) {
        is Tcp -> "tcp:$port"
        is Unix -> "unix:$path"
        Helper -> "helper"
    }

    companion object {
        fun parse(value: String): RemoteScreenEndpoint? = when {
            value == "helper" -> Helper
            value.startsWith("tcp:") -> value.removePrefix("tcp:").toIntOrNull()?.takeIf { it in 1..65535 }?.let(::Tcp)
            value.startsWith("unix:/") -> Unix(value.removePrefix("unix:"))
            else -> null
        }
    }
}

/** Stored preference; AUTO uses the helper's desktop detection. */
enum class RemoteScreenProtocol { AUTO, VNC, RDP;
    val storageName: String get() = name.lowercase()
    companion object {
        fun parse(value: String): RemoteScreenProtocol = entries.firstOrNull { it.storageName == value } ?: AUTO
    }
}
