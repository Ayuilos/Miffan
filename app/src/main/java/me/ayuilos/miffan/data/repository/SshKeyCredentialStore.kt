package me.ayuilos.miffan.data.repository

import android.content.Context
import me.rerere.workspace.RemoteAuthentication

/** Separate encrypted namespace for reusable SSH keys, excluded from Android backup. */
class SshKeyCredentialStore(context: Context) {
    private val encrypted = RemoteHostCredentialStore(
        context = context,
        directoryName = "managed-ssh-keys",
        keyAlias = "miffan.managed-ssh-keys.v1",
    )

    fun save(id: String, privateKeyPem: String) {
        encrypted.save(id, RemoteAuthentication.PrivateKey(privateKeyPem))
    }

    fun load(id: String): RemoteAuthentication.PrivateKey =
        encrypted.load(id) as? RemoteAuthentication.PrivateKey
            ?: error("Managed SSH key material is invalid")

    fun hasUsableMaterial(id: String): Boolean = runCatching { load(id) }.isSuccess

    fun delete(id: String) = encrypted.delete(id)
}
