package me.ayuilos.miffan.data.ai.computer

import me.rerere.workspace.RemotePlatform
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class RevokeMiffanKeyCommandTest {
    private val key = "ssh-ed25519 AAAA+bbb/ccc=="

    private fun run(home: Path, command: String): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).apply {
            environment()["HOME"] = home.toString()
        }.start()
        assertTrue("command timed out", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    @Test fun `posix removes only exact key tokens and preserves other bytes mode and owner`() {
        val home = Files.createTempDirectory("revoke ' dollar\$ backtick` ")
        try {
            val file = Files.createDirectories(home.resolve(".ssh")).resolve("authorized_keys")
            val kept = "# $key commented\r\n  # $key commented\nssh-rsa AAAA+bbb/ccc== other type\n" +
                "ssh-ed25519 AAAA+bbb/ccc==suffix other key\nssh-ed25519 OTHER comment $key\nlast line without newline"
            val removed = "  $key comment with ' \$() `x`\r\n$key\n" +
                "command=\"echo \\\"space here\\\"\",no-pty $key options\n"
            Files.writeString(file, removed + kept)
            val mode = PosixFilePermissions.fromString("rw-r-----")
            Files.setPosixFilePermissions(file, mode)
            val owner = Files.getOwner(file)
            val helper = Files.createDirectories(home.resolve(".miffan/bin")).resolve("miffan")
            Files.writeString(helper, "[ \"\$1 \$2\" = 'vnc stop' ] && test -f \"\$HOME/.ssh/authorized_keys\"\n")
            val command = revokeMiffanKeyCommand("$key O'Brien; \$(touch INJECTED) `oops`", RemotePlatform.POSIX)
            assertFalse(command.contains('\n'))
            val syntax = ProcessBuilder("sh", "-n").start()
            syntax.outputStream.bufferedWriter().use { it.write(command) }
            assertTrue(syntax.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, syntax.exitValue())
            assertEquals(0 to "", run(home, command))
            assertEquals(kept, Files.readString(file))
            assertEquals(mode, Files.getPosixFilePermissions(file))
            assertEquals(owner, Files.getOwner(file))
            assertFalse(Files.exists(home.resolve(".miffan")))
            assertEquals(listOf("authorized_keys"), Files.list(file.parent).use { it.map { p -> p.fileName.toString() }.toList() })
            assertEquals(0 to "", run(home, command))
            assertEquals(kept, Files.readString(file))
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `missing key file is a no-op and failed helper keeps key`() {
        val home = Files.createTempDirectory("revoke-missing")
        try {
            val command = revokeMiffanKeyCommand(key, RemotePlatform.POSIX)
            assertEquals(0 to "", run(home, command))
            assertFalse(Files.exists(home.resolve(".ssh")))
            val file = Files.createDirectories(home.resolve(".ssh")).resolve("authorized_keys")
            Files.writeString(file, "$key\n")
            val helper = Files.createDirectories(home.resolve(".miffan/bin")).resolve("miffan")
            Files.writeString(helper, "exit 17\n")
            assertEquals(17, run(home, command).first)
            assertEquals("$key\n", Files.readString(file))
            assertTrue(Files.exists(helper))
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `posix removes scheduled tasks first and a failing runner does not block revocation`() {
        val home = Files.createTempDirectory("revoke-tasks")
        try {
            val file = Files.createDirectories(home.resolve(".ssh")).resolve("authorized_keys")
            Files.writeString(file, "$key\n")
            val bin = Files.createDirectories(home.resolve(".miffan/bin"))
            Files.writeString(bin.resolve("miffan"), "exit 0\n")
            // The runner records its arguments outside ~/.miffan, which revocation deletes afterwards.
            Files.writeString(bin.resolve("miffan-task-runner"), "echo \"\$*\" > \"\$HOME/runner-args\"; exit 3\n")
            val command = revokeMiffanKeyCommand(key, RemotePlatform.POSIX)
            assertEquals(0 to "", run(home, command))
            assertEquals("task remove --all\n", Files.readString(home.resolve("runner-args")))
            assertFalse(Files.exists(home.resolve(".miffan")))
            assertEquals("", Files.readString(file))
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `windows pattern removes exact lines with comments options and CRLF`() {
        val pattern = Regex(windowsMiffanKeyPattern("ssh-ed25519", "AAAA+bbb/ccc=="))
        val kept = "# $key\r\n  # $key\r\nssh-rsa AAAA+bbb/ccc==\r\n" +
            "ssh-ed25519 AAAA+bbb/ccc==suffix\r\nssh-ed25519 OTHER comment $key\r\n" +
            "command=\"unclosed option\r\nlast line"
        assertEquals(kept, pattern.replace(" \t$key comment\r\n$key\n" +
            "command=\"echo \\\"odd space\\\"\",no-pty $key\r\n" + kept, ""))
        assertEquals("", pattern.replace(key, ""))
        val command = revokeMiffanKeyCommand("$key O'Brien \$(oops)", RemotePlatform.WINDOWS)
        assertFalse(command.contains('\n'))
        assertTrue(command.contains("administrators_authorized_keys"))
        assertTrue(command.contains("Cannot edit authorized keys:"))
        assertTrue(command.contains("[IO.FileAccess]::ReadWrite"))
        assertTrue(command.contains("Unregister-ScheduledTask -InputObject"))
        assertTrue(command.indexOf("Remove-Item -LiteralPath \$root") < command.indexOf("\$file.stream.Write"))
        assertFalse(command.contains("O'Brien"))
        assertFalse(command.contains("Set-Acl")) // In-place writes retain the existing ACL.
    }

    @Test fun `invalid key cannot introduce commands`() {
        for (platform in RemotePlatform.entries) {
            for (invalid in listOf("ssh-ed25519 AAAA;touch", "ssh-ed25519 AAAA\nother", "bad' AAAA", "ssh-ed25519")) {
                assertThrows(IllegalArgumentException::class.java) { revokeMiffanKeyCommand(invalid, platform) }
            }
        }
    }
}
