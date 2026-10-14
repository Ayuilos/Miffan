package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import io.mockk.*
import java.io.File
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RemoteSunshineUnpairTest {
    private fun fixture(name: String): File = sequenceOf(File("tools/windows-helper-tests/$name"), File("../tools/windows-helper-tests/$name"))
        .first { it.isFile }

    @Test fun `fingerprint hashes the DER certificate independent of PEM line endings`() {
        val pem = fixture("certificate.pem").readText()
        val expected = fixture("certificate.sha256").readText().trim()
        assertEquals(expected, streamClientCertificateSha256(pem))
        assertEquals(expected, streamClientCertificateSha256(pem.replace("\n", "\r\n")))
        val pemHash = MessageDigest.getInstance("SHA-256").digest(pem.toByteArray()).joinToString("") { "%02x".format(it) }
        assertNotEquals(pemHash, streamClientCertificateSha256(pem))
    }

    @Test fun `invalid certificates cannot produce an unpair fingerprint`() {
        assertThrows(java.security.cert.CertificateException::class.java) { streamClientCertificateSha256("invalid") }
    }
    @Test fun `both platforms refresh bundled helper before dispatching only the phone fingerprint`() {
        for (platform in listOf(RemotePlatform.POSIX, RemotePlatform.WINDOWS)) {
            val ssh = mockk<RemoteWorkspaceSession>()
            val assets = mockk<AssetManager>()
            val commands = mutableListOf<String>()
            every { ssh.platform } returns platform
            every { assets.open(any()) } answers { File("src/main/assets/" + firstArg<String>()).inputStream() }
            val expected = fixture("certificate.sha256").readText().trim()
            every { ssh.execute(any(), any(), any(), any(), any()) } answers {
                val command = firstArg<String>(); commands += command
                if (command.endsWith("sunshine unpair $expected")) {
                    WorkspaceCommandResult(0, """{"removed":1,"restarted":true}""", "")
                } else {
                    if (arg<java.io.InputStream?>(4) != null) {
                        val bytes = arg<java.io.InputStream?>(4)!!.readBytes()
                        assertArrayEquals(File("src/main/assets/remote/" + if (platform == RemotePlatform.WINDOWS) "miffan.ps1" else "miffan.sh").readBytes(), bytes)
                    }
                    WorkspaceCommandResult(0, "old-version", "")
                }
            }
            RemoteSunshineUnpair(assets) { fixture("certificate.pem").readText() }.unpair(ssh)
            assertEquals(3, commands.size)
            assertTrue(commands.last().endsWith("sunshine unpair $expected"))
            assertFalse(commands.any { "revoke" in it })
        }
    }

    @Test fun `failed or malformed helper result prevents successful unpairing`() {
        for (result in listOf(WorkspaceCommandResult(1, "", "administrator rights required"),
            WorkspaceCommandResult(0, "invalid", ""), WorkspaceCommandResult(0, """{"removed":-1,"restarted":false}""", ""))) {
            val ssh = mockk<RemoteWorkspaceSession>()
            val assets = mockk<AssetManager>()
            every { ssh.platform } returns RemotePlatform.POSIX
            every { assets.open(any()) } answers { File("src/main/assets/" + firstArg<String>()).inputStream() }
            every { ssh.execute(any(), any(), any(), any(), any()) } returns result
            assertThrows(IllegalStateException::class.java) {
                RemoteSunshineUnpair(assets) { fixture("certificate.pem").readText() }.unpair(ssh)
            }
        }
    }

}
