package me.rerere.workspace

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PersistentRemoteShellTest(private val executable: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun shells(): List<Array<String>> = listOf("/bin/sh", "/bin/dash")
            .filter { File(it).canExecute() }
            .distinctBy { File(it).canonicalPath }
            .map { arrayOf(it) }
    }

    private fun shell(pauseAfterUpload: Boolean = false): PersistentRemoteShell {
        val process = ProcessBuilder(executable).redirectErrorStream(true).start()
        val output = if (pauseAfterUpload) object : FilterOutputStream(process.outputStream) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val text = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
                // Keep the remote parser busy after READY. Input sent before BEGIN will
                // share its next read with the wrapper, exposing dash's stdin read-ahead.
                val delayed = if (text.contains("_READY")) {
                    text.removeSuffix("\n") + "; sleep 0.1\n"
                } else text
                out.write(delayed.toByteArray())
            }
        } else process.outputStream
        return PersistentRemoteShell(process.inputStream, output, {
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

    @Test fun inputSentFromReadyIsNotBufferedByTheShellParser() = runBlocking {
        shell(pauseAfterUpload = true).use { shell ->
            val result = withTimeout(5_000) {
                shell.execute("read -r value; printf '%s' \"\$value\"",
                    onReady = { shell.inputWriter().invoke("immediate input\n".toByteArray()) })
            }
            assertEquals(0, result.exitCode)
            assertEquals("immediate input", result.stdout)
            assertEquals("alive", shell.execute("printf alive").stdout)
        }
    }

    @Test fun closingBeforeBeginUnblocksExecutionWithoutOfferingAKeyboard() = runBlocking {
        val received = LinkedBlockingQueue<ByteArray>()
        val input = object : InputStream() {
            override fun read(): Int = error("Use bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val chunk = received.take()
                if (chunk.isEmpty()) return -1
                check(chunk.size <= length)
                chunk.copyInto(bytes, offset)
                return chunk.size
            }
        }
        lateinit var shell: PersistentRemoteShell
        val output = object : OutputStream() {
            override fun write(value: Int) = error("Use bulk writes")
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val text = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
                val token = Regex("MIFFAN_([0-9a-f]+)_READY").find(text)?.groupValues?.get(1)
                if (token != null) {
                    received.put("\u001eMIFFAN_${token}_READY\u001f".toByteArray())
                } else {
                    // Close synchronously during dispatch, before any remote BEGIN.
                    shell.close()
                }
            }
        }
        shell = PersistentRemoteShell(input, output, { received.offer(byteArrayOf()) })
        shell.use {
            var offeredKeyboard = false
            val result = withTimeout(5_000) {
                shell.execute("read -r value", onReady = { offeredKeyboard = true })
            }
            assertEquals(-1, result.exitCode)
            assertFalse(offeredKeyboard)
            assertFalse(shell.isOpen)
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
