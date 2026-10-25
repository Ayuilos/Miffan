package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.InputStream
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RemoteHelperVersionTest {
    @Test fun `older helpers refresh while current POSIX 23 and Windows 13 stay installed`() {
        for ((platform, installedVersion, refresh) in listOf(
            Triple(RemotePlatform.POSIX, "22", true),
            Triple(RemotePlatform.POSIX, "23", false),
            Triple(RemotePlatform.WINDOWS, "12", true),
            Triple(RemotePlatform.WINDOWS, "13", false),
        )) {
            val fileName = if (platform == RemotePlatform.WINDOWS) "miffan.ps1" else "miffan.sh"
            val bytes = File(System.getProperty("remoteHelper.assets"), fileName).readBytes()
            val assets = mockk<AssetManager>()
            val ssh = mockk<RemoteWorkspaceSession>()
            every { assets.open("remote/$fileName") } answers { bytes.inputStream() }
            every { ssh.platform } returns platform
            var uploaded: ByteArray? = null
            var calls = 0
            every { ssh.execute(any(), any(), any(), any(), any()) } answers {
                calls++
                val input = arg<InputStream?>(4)
                if (input == null) WorkspaceCommandResult(0, installedVersion, "")
                else {
                    uploaded = input.readBytes()
                    WorkspaceCommandResult(0, "", "")
                }
            }
            RemoteMiffanHelper(assets).ensureInstalled(ssh)
            assertEquals(if (refresh) 2 else 1, calls)
            if (refresh) assertArrayEquals(bytes, uploaded) else assertNull(uploaded)
        }
    }

    @Test fun `generated helpers match their immutable version locks`() {
        val rows = File(requireNotNull(System.getProperty("remoteHelper.versions"))).readLines()
            .filter { it.isNotBlank() }.map { it.split(" ") }
        assertTrue(rows.all { it.size == 3 })
        assertEquals("Duplicate helper version in versions.lock", rows.size, rows.map { it.take(2) }.distinct().size)
        for ((name, pattern) in mapOf("miffan.sh" to "MIFFAN_HELPER_VERSION=(\\d+)",
            "miffan.ps1" to "\\\$script:HelperVersion = (\\d+)")) {
            val message = "$name helper 内容变了：升 MIFFAN_HELPER_VERSION / \$script:HelperVersion，然后更新 versions.lock"
            val bytes = File(System.getProperty("remoteHelper.assets"), name).readBytes()
            val version = requireNotNull(Regex(pattern).find(bytes.toString(Charsets.UTF_8))) { message }.groupValues[1]
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val row = rows.singleOrNull { it[0] == name && it[1] == version }
            assertNotNull(message, row)
            assertEquals(message, row!![2], sha)
            assertEquals("Helper version must advance monotonically", rows.filter { it[0] == name }.maxOf { it[1].toInt() }, version.toInt())
        }
    }
}
