package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity

@Dao
interface RemoteHostDAO {
    @Query("SELECT * FROM remote_hosts ORDER BY name COLLATE NOCASE")
    fun listFlow(): Flow<List<RemoteHostEntity>>

    @Query("SELECT * FROM remote_hosts WHERE id = :id")
    suspend fun getById(id: String): RemoteHostEntity?

    @Query("SELECT COUNT(*) FROM remote_hosts WHERE ssh_key_id = :sshKeyId")
    suspend fun countBySshKeyId(sshKeyId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(host: RemoteHostEntity)

    @Update
    suspend fun update(host: RemoteHostEntity): Int

    /** SSH identity edits never replace screen settings or a concurrently confirmed RDP pin. */
    suspend fun updateConnection(host: RemoteHostEntity): Int = updateConnectionFields(host.id,
        host.name, host.host, host.port, host.username, host.authType, host.sshKeyId,
        host.trustedHostKeySha256, host.connectionRevision, host.updatedAt)

    @Query("""UPDATE remote_hosts SET name=:name, host=:address, port=:port, username=:username,
        auth_type=:authType, ssh_key_id=:sshKeyId, trusted_host_key_sha256=:sshPin,
        connection_revision=:revision, updated_at=:updatedAt WHERE id=:id""")
    suspend fun updateConnectionFields(id: String, name: String, address: String, port: Int,
        username: String, authType: String, sshKeyId: String?, sshPin: String?, revision: String, updatedAt: Long): Int

    @Query("""UPDATE remote_hosts SET screen_enabled = :enabled, screen_endpoint = :endpoint,
        screen_auth = :auth, screen_username = :username,
        screen_protocol = COALESCE(:protocol, screen_protocol),
        rdp_username = COALESCE(:rdpUsername, rdp_username),
        stream_enabled = COALESCE(:streamEnabled, stream_enabled), updated_at = :updatedAt WHERE id = :id""")
    suspend fun updateScreenConfig(id: String, enabled: Boolean, endpoint: String, auth: String,
        username: String, protocol: String?, rdpUsername: String?, updatedAt: Long, streamEnabled: Boolean? = null): Int

    @Query("UPDATE remote_hosts SET stream_enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setStreamEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE remote_hosts SET stream_certificate_sha256 = :pin, updated_at = :updatedAt WHERE id = :id")
    suspend fun pinStreamCertificate(id: String, pin: String, updatedAt: Long): Int

    /** Pairing cannot overwrite a concurrent trust decision or a changed SSH identity. */
    @Query("""UPDATE remote_hosts SET stream_certificate_sha256 = :pin, updated_at = :updatedAt
        WHERE id = :id AND connection_revision = :revision AND stream_certificate_sha256 IS :expectedPin""")
    suspend fun pinPairedStreamCertificate(id: String, pin: String, revision: String, expectedPin: String?, updatedAt: Long): Int

    @Query("UPDATE remote_hosts SET screen_platform = :platform, rdp_username = COALESCE(:username, rdp_username) WHERE id = :id")
    suspend fun updateDetectedScreen(id: String, platform: String, username: String?): Int

    @Query("UPDATE remote_hosts SET rdp_certificate_sha256 = :pin, updated_at = :updatedAt WHERE id = :id")
    suspend fun pinRdpCertificate(id: String, pin: String, updatedAt: Long): Int

    /** Never replace a concurrent confirmation or pin a different SSH host identity. */
    @Query("""UPDATE remote_hosts SET rdp_certificate_sha256 = :pin, updated_at = :updatedAt
        WHERE id = :id AND rdp_certificate_sha256 IS NULL AND connection_revision = :revision""")
    suspend fun pinFirstRdpCertificate(id: String, pin: String, revision: String, updatedAt: Long): Int

    @Query("DELETE FROM remote_hosts WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
