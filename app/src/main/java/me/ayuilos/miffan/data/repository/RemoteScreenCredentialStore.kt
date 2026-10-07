package me.ayuilos.miffan.data.repository

import android.content.Context
import me.rerere.workspace.RemoteAuthentication

/** Separate encrypted namespace for VNC / macOS screen-sharing passwords, keyed by host id. */
class RemoteScreenCredentialStore(context: Context) {
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

    fun delete(hostId: String) = encrypted.delete(hostId)
}
