package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-host test. The only writable tree is the P7a test directory. No GUI/config changes. */
class WindowsWorkspaceIntegrationTest {
    @Test fun dotNetWorkingDirectoryMatchesPowerShellInChineseDirectory() = withWindowsWorkspace { ssh ->
        val cwd = "中文 空格 O'Brien"
        ssh.mkdir(cwd)
        // Set-Location alone leaves .NET CurrentDirectory at the SSH account home on PS 5.1.
        // Read both paths without using .NET relative writes in the test itself.
        val result = ssh.execute("[Console]::Out.Write([Environment]::CurrentDirectory)", cwd)
        assertEquals(0, result.exitCode)
        assertEquals(WindowsRemotePath.toLocal("${ssh.resolvedRoot}/$cwd").lowercase(), result.stdout.lowercase())
        assertEquals(result.stdout, ssh.execute("[Console]::Out.Write((Get-Location).ProviderPath)", cwd).stdout)
    }

    @Test fun powershellAndSftpOnWindowsOpenSsh() = withWindowsWorkspace { ssh ->
        val root = ssh.resolvedRoot
        assertThrows(RemoteTerminalUnsupportedException::class.java) { ssh.openTerminal() }
        ssh.mkdir("中文 空格 O'Brien")
        val cwd = "中文 空格 O'Brien"
        val text = "中文 stdin\n第二行 ' \" & | < > 数据"
        val result = ssh.execute(
            "[Console]::Out.Write([Console]::In.ReadToEnd()); [Console]::Error.Write('中文 stderr'); exit 7",
            cwd, stdin = text.byteInputStream(),
        )
        assertEquals(7, result.exitCode)
        assertEquals(text, result.stdout)
        assertEquals("中文 stderr", result.stderr)
        val directory = ssh.execute("[Console]::Out.Write((Get-Location).Path)", cwd)
        assertEquals(0, directory.exitCode)
        assertEquals(WindowsRemotePath.toLocal("$root/$cwd").lowercase(), directory.stdout.lowercase())
        val native = ssh.execute("cmd /c exit 9")
        assertEquals(9, native.exitCode)
        val nativeStreams = ssh.execute("cmd /c 'echo stdout & echo stderr 1>&2 & exit 0'")
        assertEquals(0, nativeStreams.exitCode)
        assertTrue(nativeStreams.stdout.contains("stdout"))
        assertTrue(nativeStreams.stderr.contains("stderr"))
        val error = ssh.execute("Write-Output '中文 stdout'; Write-Error '中文 error'")
        assertEquals(1, error.exitCode)
        assertTrue(error.stdout.contains("中文 stdout"))
        assertTrue(error.stderr.contains("中文 error"))
        assertFalse(error.stderr.contains("CLIXML"))
        val exception = ssh.execute("throw '中文 exception'")
        assertEquals(1, exception.exitCode)
        assertTrue(exception.stderr.contains("中文 exception"))
        val timeout = ssh.execute("Write-Output 'started'; Start-Sleep -Milliseconds 3000", timeoutMillis = 1500)
        assertTrue(timeout.timedOut)
        assertEquals(-1, timeout.exitCode)
        assertTrue(timeout.stdout.contains("started"))
        val truncated = ssh.execute("[Console]::Out.Write('x' * 2048)", maxOutputBytes = 128)
        assertTrue(truncated.truncated)
        assertEquals(128, truncated.stdout.toByteArray().size)
        assertEquals(0, truncated.exitCode)
        val binary = byteArrayOf(0, 1, 127, -128, -1) + "中文原始 stdin".toByteArray()
        val binaryPath = WindowsRemotePath.toLocal("$root/binary stdin.bin").replace("'", "''")
        val binaryStdin = ssh.execute("""
            ${'$'}file = [IO.File]::Create('$binaryPath')
            try { [Console]::OpenStandardInput().CopyTo(${'$'}file) } finally { ${'$'}file.Dispose() }
        """.trimIndent(), stdin = binary.inputStream())
        assertEquals(0, binaryStdin.exitCode)
        assertArrayEquals(binary, ssh.readBytes("binary stdin.bin"))
        val name = "$cwd/文件 space 'quote.txt"
        ssh.importFile(name, "原始中文".byteInputStream())
        assertEquals("原始中文", ssh.readText(name))
        assertTrue(ssh.list(cwd).any { it.name == "文件 space 'quote.txt" })
        ssh.importFile(name, ssh.readText(name).replace("原始", "编辑").byteInputStream())
        assertEquals("编辑中文", ssh.readText(name))
        // Failure during upload must leave the original intact and clean staging.
        val broken = object : InputStream() {
            var delivered = false
            override fun read(): Int = throw IOException("injected upload failure")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (delivered) throw IOException("injected upload failure")
                delivered = true
                bytes[offset] = 65
                return 1
            }
        }
        assertThrows(Exception::class.java) { ssh.importFile(name, broken) }
        assertEquals("编辑中文", ssh.readText(name))
        assertEquals(1, ssh.list(cwd).size)
        // Native replacement failure must preserve the original and clean staging.
        // Lock only this test file, signal readiness in the same test directory.
        val locked = java.util.concurrent.CompletableFuture.runAsync {
            val lockPath = WindowsRemotePath.toLocal("$root/$name").replace("'", "''")
            val signalPath = WindowsRemotePath.toLocal("$root/lock-ready").replace("'", "''")
            val lock = ssh.execute("""
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}f = [IO.File]::Open('$lockPath', 'Open', 'Read', 'Read')
                try { [IO.File]::WriteAllText('$signalPath', 'ready'); Start-Sleep -Seconds 4 }
                finally { ${'$'}f.Dispose() }
            """.trimIndent(), timeoutMillis = 10_000)
            check(lock.exitCode == 0) { lock.stderr }
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (runCatching { ssh.readText("lock-ready") }.getOrNull() != "ready") {
            check(System.nanoTime() < deadline) { "File lock did not start" }
            Thread.sleep(40)
        }
        assertThrows(Exception::class.java) { ssh.importFile(name, "should fail".byteInputStream()) }
        locked.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("编辑中文", ssh.readText(name))
        assertEquals(1, ssh.list(cwd).size)
        val renamed = "$cwd/重命名 space.txt"
        ssh.move(name, renamed)
        assertEquals("编辑中文", ssh.readText(renamed))
        assertTrue(ssh.delete(renamed))
        assertTrue(ssh.list(cwd).isEmpty())
    }

    private fun withWindowsWorkspace(block: (RemoteWorkspaceSession) -> Unit) {
        val file = File(System.getProperty("miffan.p7a.testProperties", ".deps/windows-spike/p7-test.properties"))
        assumeTrue("No Windows P7a test properties", file.isFile)
        val props = Properties().apply { file.inputStream().use(::load) }
        val host = requireNotNull(props.getProperty("host"))
        val port = props.getProperty("port", "22").toInt()
        // The dedicated key file is passed straight to JSch, never read or printed by the test.
        val authentication = RemoteAuthentication.PrivateKey(pem = "", filePath = requireNotNull(props.getProperty("key")))
        val pin = NativeSshWorkspaceTransport.discoverHostKey(host, port).sha256Fingerprint
        val home = "/C:/Users/Ekk"
        val base = "$home/miffan-p7a-test"
        val run = "run-${UUID.randomUUID()}"
        val config = RemoteHostConfig(host, port, requireNotNull(props.getProperty("user")), authentication, pin, home)
        NativeSshWorkspaceTransport.open(config).use { setup ->
            assertEquals(RemotePlatform.WINDOWS, setup.platform)
            assertEquals(home.lowercase(), setup.homeDirectory.lowercase())
            assertFalse(setup.supportsInteractiveTerminal)
            setup.mkdir("miffan-p7a-test/$run")
            try {
                NativeSshWorkspaceTransport.open(config.copy(remoteRoot = "$base/$run")).use(block)
            } finally {
                setup.delete("miffan-p7a-test/$run", recursive = true)
                // Delete only an empty test parent; never erase another concurrent run.
                runCatching { setup.delete("miffan-p7a-test", recursive = false) }
            }
        }
    }
}
