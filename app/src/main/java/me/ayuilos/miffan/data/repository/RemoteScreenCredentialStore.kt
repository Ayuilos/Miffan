package me.ayuilos.miffan.data.repository

import android.content.Context
import me.rerere.workspace.RemoteAuthentication

/** Separate encrypted namespaces for VNC / macOS and RDP passwords, keyed by host id. */
class RemoteScreenCredentialStore(context: Context) {
    private val rdp = RemoteHostCredentialStore(context, "remote-rdp-credentials", "miffan.remote-rdp-credentials.v1")
    private val encrypted = RemoteHostCredentialStore(
        context = context,
        directoryName = "remote-screen-credentials",
        keyAlias = "miffan.remote-screen-credentials.v1",
    )

    fun save(hostId: String, password: String) {
        encrypted.save(hostId, RemoteAuthentication.Password(password))
    }

    fun load(hostId: String): String? = runCatching {
        (encrypted.load(hostId) as? RemoteAuthentication.Password)?.value
    }.getOrNull()

    fun saveRdp(hostId: String, password: String) = rdp.save(hostId, RemoteAuthentication.Password(password))
    fun loadRdp(hostId: String): String? = runCatching { (rdp.load(hostId) as? RemoteAuthentication.Password)?.value }.getOrNull()
    @Synchronized fun getOrCreateRdp(hostId: String): String = loadRdp(hostId) ?: ByteArray(32).also {
        java.security.SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it.toInt() and 255) }.also { saveRdp(hostId, it) }
    fun delete(hostId: String) { encrypted.delete(hostId); rdp.delete(hostId) }
    fun deleteVnc(hostId: String) = encrypted.delete(hostId)
}
