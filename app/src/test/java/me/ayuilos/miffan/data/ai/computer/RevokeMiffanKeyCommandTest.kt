package me.ayuilos.miffan.data.ai.computer

import me.ayuilos.miffan.data.remotetask.sshKeyFingerprint
import me.rerere.workspace.RemotePlatform
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class RevokeMiffanKeyCommandTest {
    private val key = "ssh-ed25519 AAAA+bbb/ccc"

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

    private fun task(home: Path, id: String, sshKey: String?) {
        val dir = Files.createDirectories(home.resolve(".miffan/tasks/$id"))
        val marker = sshKey?.let { ",\"sshKey\":\"$it\"" }.orEmpty()
        Files.writeString(dir.resolve("meta.json"), "{\"name\":\"$id\"$marker,\"createdAt\":1}")
    }

    @Test fun `fingerprint matches ssh-keygen`() {
        assertEquals("SHA256:uWJv/xekyJNrljAxxpfqmBft1TWUj6JRvEm+I/WNsW8",
            sshKeyFingerprint("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL3moEC/xI773F/LDCspONLkdH0HrJzx3B6k4Yoj6f90 miffan"))
    }

    @Test fun `posix removes only this key's tasks and keeps what other phones' tasks need`() {
        val home = Files.createTempDirectory("revoke-tasks")
        try {
            val file = Files.createDirectories(home.resolve(".ssh")).resolve("authorized_keys")
            Files.writeString(file, "$key\n")
            val bin = Files.createDirectories(home.resolve(".miffan/bin"))
            Files.writeString(bin.resolve("miffan"), "exit 0\n")
            // The runner logs outside ~/.miffan and removes the task like the helper does; a failure must not block revocation.
            Files.writeString(bin.resolve("miffan-task-runner"),
                "echo \"\$*\" >> \"\$HOME/runner-args\"; rm -rf \"\$HOME/.miffan/tasks/\$3\"; exit 3\n")
            task(home, "mine", sshKeyFingerprint(key))
            task(home, "legacy", null)
            task(home, "other", "SHA256:someoneElse")
            assertEquals(0 to "", run(home, revokeMiffanKeyCommand(key, RemotePlatform.POSIX)))
            assertEquals(setOf("task remove mine", "task remove legacy"),
                Files.readAllLines(home.resolve("runner-args")).toSet())
            assertEquals(setOf("other"), names(home.resolve(".miffan/tasks")))
            // Timers of the remaining task call the runner, so it stays; the rest of the helper goes.
            assertEquals(setOf("miffan-task-runner"), names(bin))
            assertEquals("", Files.readString(file))
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `posix removes the whole helper when no other phone has tasks`() {
        val home = Files.createTempDirectory("revoke-own-tasks")
        try {
            val bin = Files.createDirectories(home.resolve(".miffan/bin"))
            Files.writeString(bin.resolve("miffan-task-runner"), "rm -rf \"\$HOME/.miffan/tasks/\$3\"\n")
            task(home, "mine", sshKeyFingerprint(key))
            assertEquals(0 to "", run(home, revokeMiffanKeyCommand(key, RemotePlatform.POSIX)))
            assertFalse(Files.exists(home.resolve(".miffan")))
        } finally { home.toFile().deleteRecursively() }
    }

    private fun names(dir: Path): Set<String> = Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList().toSet() }

    @Test fun `posix keeps partners files unless asked and removes everything else of the helper`() {
        val home = Files.createTempDirectory("revoke-work")
        try {
            Files.createDirectories(home.resolve(".ssh")).resolve("authorized_keys").let { Files.writeString(it, "$key\n") }
            val miffan = home.resolve(".miffan")
            Files.createDirectories(miffan.resolve("bin"))
            Files.createDirectories(miffan.resolve("tasks/.staging"))
            Files.writeString(miffan.resolve(".hidden"), "x")
            Files.createDirectories(miffan.resolve("conversations/c1")).resolve("out.txt").let { Files.writeString(it, "kept") }
            Files.createDirectories(miffan.resolve("skills/s1"))
            assertEquals(0 to "", run(home, revokeMiffanKeyCommand(key, RemotePlatform.POSIX)))
            assertEquals(setOf("conversations", "skills"), names(miffan))
            assertEquals("kept", Files.readString(miffan.resolve("conversations/c1/out.txt")))

            assertEquals(0 to "", run(home, revokeMiffanKeyCommand(key, RemotePlatform.POSIX, home.toString())))
            assertFalse(Files.exists(miffan))
        } finally { home.toFile().deleteRecursively() }
    }

    @Test fun `posix deletes work files in a workspace folder outside home and nothing else there`() {
        val home = Files.createTempDirectory("revoke-home")
        val root = Files.createTempDirectory("work ' \$(touch INJECTED) ")
        try {
            Files.createDirectories(home.resolve(".miffan/conversations/c0"))
            Files.createDirectories(root.resolve(".miffan/conversations/c1"))
            Files.createDirectories(root.resolve(".miffan/skills/s1"))
            Files.writeString(root.resolve(".miffan/notes.md"), "mine")
            val command = revokeMiffanKeyCommand(key, RemotePlatform.POSIX, root.toString())
            assertEquals(0 to "", run(home, command))
            assertEquals(setOf("notes.md"), names(root.resolve(".miffan")))
            // The home folder is not the workspace here, so its partners' files stay.
            assertEquals(setOf("conversations"), names(home.resolve(".miffan")))
            assertFalse(Files.exists(home.resolve("INJECTED")))
        } finally { home.toFile().deleteRecursively(); root.toFile().deleteRecursively() }
    }

    @Test fun `windows deletes work files only when asked`() {
        assertFalse(revokeMiffanKeyCommand(key, RemotePlatform.WINDOWS).contains("'.miffan'"))
        val command = revokeMiffanKeyCommand(key, RemotePlatform.WINDOWS, "/C:/Users/O'Brien")
        assertFalse(command.contains('\n'))
        assertTrue(command.contains("Join-Path 'C:\\Users\\O''Brien' '.miffan'"))
        assertTrue(command.contains("@('conversations', 'skills')"))
        assertTrue(command.indexOf("\$work = ") < command.indexOf("\$file.stream.Write"))
    }

    @Test fun `work files folder must be absolute and single line`() {
        for (invalid in listOf("relative/path", "/home/user\n/rm", "")) {
            assertThrows(IllegalArgumentException::class.java) { revokeMiffanKeyCommand(key, RemotePlatform.POSIX, invalid) }
        }
    }

    @Test fun `windows pattern removes exact lines with comments options and CRLF`() {
        val pattern = Regex(windowsMiffanKeyPattern("ssh-ed25519", "AAAA+bbb/ccc"))
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
