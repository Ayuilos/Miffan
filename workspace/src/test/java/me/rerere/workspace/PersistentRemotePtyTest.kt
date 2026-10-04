package me.rerere.workspace

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises canonical line limits and real terminal input, not just pipes pretending to be a PTY. */
class PersistentRemotePtyTest {
    @Test fun realPtyPreservesTtyAndParentProcessAcrossInteractiveAndAutomaticCommands() = runBlocking {
        assumeTrue("Unix Python PTY fixture required", File("/usr/bin/python3").canExecute())
        val bridge = """
            import os, pty, select, sys
            pid, fd = pty.fork()
            if pid == 0:
                os.execv('/bin/sh', ['/bin/sh', '-c', 'stty -echo; exec env ENV= PS1= PS2= /bin/sh +i'])
            try:
                while True:
                    ready, _, _ = select.select([fd, 0], [], [])
                    for src in ready:
                        try:
                            data = os.read(src, 8192)
                        except OSError:
                            sys.exit(0)
                        if not data:
                            sys.exit(0)
                        dest = 1 if src == fd else fd
                        while data:
                            data = data[os.write(dest, data):]
            finally:
                os.close(fd)
        """.trimIndent()
        val process = ProcessBuilder("/usr/bin/python3", "-u", "-c", bridge).redirectErrorStream(true).start()
        PersistentRemoteShell(process.inputStream, process.outputStream, { process.destroyForcibly() }).use { shell ->
            val prompt = CompletableDeferred<Unit>()
            val first = async {
                shell.execute("printf 'Password: '; read -r reply; test \"\$reply\" = 'private value' || return 8; unset reply; tty; printf '%s\\n' \"\$\$\"; sh -c 'printf \"%s\\n\" \"\$PPID\"'",
                    timeoutMillis = 5_000,
                    onOutput = { if (it.toString(Charsets.UTF_8).contains("Password: ")) prompt.complete(Unit) })
            }
            withTimeout(5_000) { prompt.await() }
            assertFalse(first.isCompleted)
            shell.write("private value\n".toByteArray())
            val result = withTimeout(5_000) { first.await() }
            assertEquals(0, result.exitCode)
            assertFalse(result.stdout.contains("private value"))
            val identity = result.stdout.removePrefix("Password: ").trim().lines().map { it.trim() }
            assertTrue(identity.first().startsWith("/dev/"))
            assertEquals(identity[1], identity[2])
            val next = shell.execute("tty; printf '%s\\n' \"\$\$\"; sh -c 'printf \"%s\\n\" \"\$PPID\"'", timeoutMillis = 5_000)
            assertEquals(identity, next.stdout.trim().lines().map { it.trim() })
            // Well over the PTY's canonical line limit, including a surrogate boundary.
            val text = "a".repeat(243) + "🍚" + "b".repeat(12_000)
            val large = shell.execute("printf '%s' '$text'", timeoutMillis = 5_000)
            assertEquals(0, large.exitCode)
            assertEquals(text, large.stdout)
        }
    }
}
