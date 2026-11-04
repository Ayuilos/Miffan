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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RemoteHelperVersionTest {
    @Test fun `older helpers refresh while current POSIX 48 and Windows 20 stay installed`() {
        for ((platform, installedVersion, refresh) in listOf(
            Triple(RemotePlatform.POSIX, "47", true),
            Triple(RemotePlatform.POSIX, "48", false),
            Triple(RemotePlatform.WINDOWS, "19", true),
            Triple(RemotePlatform.WINDOWS, "20", false),
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
            // An outdated helper is checked again under the install lock before it is uploaded.
            assertEquals(if (refresh) 3 else 1, calls)
            if (refresh) assertArrayEquals(bytes, uploaded) else assertNull(uploaded)
        }
    }

    private fun windowsHelper(): Pair<AssetManager, ByteArray> {
        val bytes = File(System.getProperty("remoteHelper.assets"), "miffan.ps1").readBytes()
        val assets = mockk<AssetManager>()
        every { assets.open("remote/miffan.ps1") } answers { bytes.inputStream() }
        return assets to bytes
    }

    @Test fun `two screens opening at once upload an outdated helper only once`() {
        val (assets, bytes) = windowsHelper()
        val version = Regex("HelperVersion = (\\d+)").find(bytes.toString(Charsets.UTF_8))!!.groupValues[1]
        val ssh = mockk<RemoteWorkspaceSession>()
        every { ssh.platform } returns RemotePlatform.WINDOWS
        val installed = AtomicReference("18")
        val uploads = AtomicInteger()
        val bothChecked = CountDownLatch(2)
        every { ssh.execute(any(), any(), any(), any(), any()) } answers {
            val input = arg<InputStream?>(4)
            if (input == null) {
                bothChecked.countDown()
                // Both callers see the old version before either starts its upload.
                bothChecked.await(5, TimeUnit.SECONDS)
                WorkspaceCommandResult(0, installed.get(), "")
            } else {
                assertArrayEquals(bytes, input.readBytes())
                uploads.incrementAndGet()
                Thread.sleep(100)
                installed.set(version)
                WorkspaceCommandResult(0, "", "")
            }
        }
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = List(2) { Thread { runCatching { RemoteMiffanHelper(assets).ensureInstalled(ssh) }.onFailure { failures += it } } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(10_000) }
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(1, uploads.get())
    }

    @Test fun `a failed install says why, even with nothing on stderr`() {
        val (assets, _) = windowsHelper()
        for ((result, expected) in listOf(
            WorkspaceCommandResult(-1, "", "", timedOut = true) to "Could not install the Miffan helper: timed out after 30 s",
            WorkspaceCommandResult(1, "", "") to "Could not install the Miffan helper: exit code 1",
            WorkspaceCommandResult(1, "", "Helper upload is corrupt\n") to "Could not install the Miffan helper: Helper upload is corrupt",
        )) {
            val ssh = mockk<RemoteWorkspaceSession>()
            every { ssh.platform } returns RemotePlatform.WINDOWS
            every { ssh.execute(any(), any(), any(), any(), any()) } answers {
                if (arg<InputStream?>(4) == null) WorkspaceCommandResult(0, "18", "") else result
            }
            val error = runCatching { RemoteMiffanHelper(assets).ensureInstalled(ssh) }.exceptionOrNull()
            assertEquals(expected, error?.message)
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
