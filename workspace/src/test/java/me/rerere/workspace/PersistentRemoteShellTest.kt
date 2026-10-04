package me.rerere.workspace

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PersistentRemoteShellTest {
    private fun shell(): PersistentRemoteShell {
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        return PersistentRemoteShell(process.inputStream, process.outputStream, {
            process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
        })
    }

    @Test fun commandsKeepShellPidCwdVariablesAndExitStatus() = runBlocking {
        val directory = Files.createTempDirectory("miffan-persistent").toFile()
        try {
            shell().use { shell ->
                val first = shell.execute("export SESSION_VALUE='hello world'; printf '%s' \"\$\$\"", directory.path)
                val second = shell.execute("printf '%s|%s|%s' \"\$\$\" \"\$PWD\" \"\$SESSION_VALUE\"; false")
                assertEquals(0, first.exitCode)
                assertEquals(1, second.exitCode)
                assertEquals("${first.stdout}|${directory.path}|hello world", second.stdout)
                assertTrue(shell.isOpen)
                assertEquals("next", shell.execute("printf next").stdout)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun shortInteractivePromptIsDeliveredBeforeUserInputAndTrailerIsNeverReadAsInput() = runBlocking {
        shell().use { shell ->
            val prompted = CompletableDeferred<Unit>()
            val command = async {
                shell.execute("printf 'Input: '; read -r answer; test \"\$answer\" = 'user value'; result=\$?; unset answer; (exit \$result)",
                    onOutput = { if (it.toString(Charsets.UTF_8).contains("Input: ")) prompted.complete(Unit) })
            }
            withTimeout(5_000) { prompted.await() }
            assertFalse(command.isCompleted)
            shell.write("user value\n".toByteArray())
            val result = withTimeout(5_000) { command.await() }
            assertEquals(0, result.exitCode)
            assertEquals("Input: ", result.stdout)
            assertEquals("alive", shell.execute("printf alive").stdout)
        }
    }

    @Test fun multilineQuotesUnicodeAndLargeOutputAreFramedWithoutLeakingControlMessages() = runBlocking {
        shell().use { shell ->
            val text = "a".repeat(244) + "🍚你好 '$;\\\n" + "b".repeat(8000)
            val literal = "'" + text.replace("'", "'\\''") + "'"
            assertEquals(text, shell.execute("printf '%s' $literal").stdout)
            val result = shell.execute("i=0; while [ \$i -lt 20000 ]; do printf 0123456789; i=\$((i+1)); done")
            assertTrue(result.truncated)
            assertEquals(64 * 1024, result.stdout.length)
            assertEquals(0, result.exitCode)
            assertEquals("separate", shell.execute("printf separate").stdout)
        }
    }

    @Test fun queuedCommandDoesNotStealInteractiveInputAndCancellingWaiterKeepsActiveShell() = runBlocking {
        shell().use { shell ->
            val ready = CompletableDeferred<Unit>()
            val first = async { shell.execute("read -r response; printf '%s' \"\$response\"", onReady = { ready.complete(Unit) }) }
            ready.await()
            val cancelled = async { shell.execute("printf unwanted") }
            cancelled.cancelAndJoin()
            val next = async { shell.execute("printf second") }
            shell.write("first\n".toByteArray())
            assertEquals("first", withTimeout(5_000) { first.await() }.stdout)
            assertEquals("second", withTimeout(5_000) { next.await() }.stdout)
            assertTrue(shell.isOpen)
        }
    }

    @Test fun completedTerminalsCannotSendDelayedInputToTheNextCommand() = runBlocking {
        shell().use { shell ->
            var oldKeyboard: ((ByteArray) -> Unit)? = null
            shell.execute("true", onReady = { oldKeyboard = shell.inputWriter() })
            val ready = CompletableDeferred<Unit>()
            val next = async {
                shell.execute("read -r value; printf '%s' \"\$value\"", onReady = { ready.complete(Unit) })
            }
            ready.await()
            requireNotNull(oldKeyboard).invoke("old password\n".toByteArray())
            shell.inputWriter().invoke("new input\n".toByteArray())
            assertEquals("new input", withTimeout(5_000) { next.await() }.stdout)
        }
    }

    @Test fun timeoutAndShellExitHaveUnknownStatusAndCannotReuseBrokenSession() = runBlocking {
        shell().use { shell ->
            val result = withTimeout(5_000) { shell.execute("read -r waiting", timeoutMillis = 100) }
            assertTrue(result.timedOut)
            assertEquals(-1, result.exitCode)
            assertFalse(shell.isOpen)
            assertTrue(runCatching { shell.execute("printf must-not-run") }.isFailure)
        }
        shell().use { shell ->
            val result = withTimeout(5_000) { shell.execute("exit 7") }
            assertEquals(-1, result.exitCode)
            assertFalse(shell.isOpen)
        }
    }

    @Test fun initialDirectoryAppliesOnlyOnceAndExplicitCwdStillWorks() = runBlocking {
        val directory = Files.createTempDirectory("miffan-initial").toFile()
        try {
            shell().use { shell ->
                assertEquals(directory.path, shell.execute("printf '%s' \"\$PWD\"", initialCwd = directory.path).stdout)
                shell.execute("cd /")
                assertEquals("/", shell.execute("printf '%s' \"\$PWD\"", initialCwd = directory.path).stdout)
                assertEquals(directory.path, shell.execute("printf '%s' \"\$PWD\"", cwd = directory.path).stdout)
                assertNotEquals(0, shell.execute("printf must-not-run", cwd = directory.resolve("missing").path).exitCode)
            }
        } finally { directory.deleteRecursively() }
    }
}
