package me.rerere.workspace

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteTerminalCommandSpecTest {
    @Test fun preservesScriptQuotingMultilineAndExitCode() {
        val root = Files.createTempDirectory("terminal ' \$ root").toFile()
        try {
            val command = RemoteTerminalCommandSpec("printf '%s\\n' \"a'b\" '\$HOME'\nprintf 'err\\n' >&2\nexit 7", "/workspace")
            val process = ProcessBuilder("/bin/sh", "-c", command.shellScript(root.path))
                .redirectErrorStream(true).start()
            assertEquals("a'b\n\$HOME\nerr\n", process.inputStream.bufferedReader().readText())
            assertEquals(7, process.waitFor())
        } finally { root.deleteRecursively() }
    }

    @Test fun mapsCwdAndDoesNotRunWhenDirectoryIsMissing() {
        val root = Files.createTempDirectory("terminal-cwd").toFile()
        try {
            val sub = root.resolve("sub dir").apply { mkdir() }
            val command = RemoteTerminalCommandSpec("pwd", "/workspace/sub dir")
            val process = ProcessBuilder("/bin/sh", "-c", command.shellScript(root.path)).start()
            assertEquals(sub.canonicalPath, java.io.File(process.inputStream.bufferedReader().readText().trim()).canonicalPath)
            assertEquals(0, process.waitFor())
            val missing = RemoteTerminalCommandSpec("touch should-not-exist", "missing")
            val failed = ProcessBuilder("/bin/sh", "-c", missing.shellScript(root.path)).start()
            assertEquals(1, failed.waitFor())
            assertEquals(false, root.resolve("should-not-exist").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsEscapedCwdAndTerminalControlSequences() {
        listOf("../elsewhere", "/etc", "a/../../b").forEach { cwd ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteTerminalCommandSpec("pwd", cwd).shellScript("/srv/project")
            }
        }
        listOf("", "\u001b[2Jrm file", "echo ok\rmalicious", "echo\u0000bad").forEach { command ->
            assertThrows(IllegalArgumentException::class.java) { RemoteTerminalCommandSpec(command, "") }
        }
    }
}
