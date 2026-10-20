package me.ayuilos.miffan.data.remotetask

import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Runs the real bundled helper against [RemoteTaskClient] on this machine: a temporary HOME, a
 * stubbed systemd, and a fake agent. Proves what the app renders is what the helper accepts and runs.
 */
class RemoteTaskHelperContractTest {
    private lateinit var root: File
    private lateinit var home: File
    private lateinit var bin: File
    private val env = mutableMapOf<String, String>()

    @Before fun setUp() {
        root = Files.createTempDirectory("miffan task contract").toFile()
        home = File(root, "home").apply { mkdirs() }
        bin = File(root, "bin").apply { mkdirs() }
        val helper = File(requireNotNull(System.getProperty("remoteHelper.assets")), "miffan.sh")
        File(home, ".miffan/bin").mkdirs()
        helper.copyTo(File(home, ".miffan/bin/miffan")).setExecutable(true)
        stub("uname", """[ "${'$'}1" = -s ] && echo Linux || echo x86_64""")
        stub("loginctl", "echo yes")
        stub("timedatectl", "echo Asia/Shanghai")
        // `task run` goes through systemd; run the unit's command in the foreground instead.
        stub("systemctl", """
            case " ${'$'}* " in
              *" start "*) for unit in "${'$'}@"; do :; done; id=${'$'}{unit#miffan-task-}; id=${'$'}{id%.service}
                           /bin/sh "${'$'}HOME/.miffan/bin/miffan-task-runner" task exec "${'$'}id" ;;
            esac
            exit 0
        """.trimIndent())
        // A fake agent that is only on the login shell's PATH and needs a variable from the shell rc.
        val tools = File(root, "tools").apply { mkdirs() }
        File(tools, "fake-agent").apply {
            writeText("#!/bin/sh\necho 'warning: noisy stderr' >&2\nprompt=${'$'}(cat)\n" +
                "printf '{\"is_error\":false,\"result\":\"%s via %s in %s\"}\\n' \"${'$'}prompt\" \"${'$'}https_proxy\" \"${'$'}(pwd)\"\n")
            setExecutable(true)
        }
        val rc = File(root, "rc").apply { writeText("echo banner\nPATH=\"${tools.absolutePath}:${'$'}PATH\"\nexport https_proxy=http://proxy:7890\n") }
        env += mapOf(
            "HOME" to home.absolutePath, "PATH" to "${bin.absolutePath}:/usr/bin:/bin", "SHELL" to "/bin/sh",
            "ENV" to rc.absolutePath, "XDG_RUNTIME_DIR" to File(root, "run").absolutePath,
            "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/dev/null",
        )
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `agent task round trip through the helper`() {
        val client = RemoteTaskClient(session())
        val probe = client.probe()
        assertEquals("systemd", probe.scheduler)
        assertEquals("Asia/Shanghai", probe.timezone)

        val tool = AgentToolDefinition(name = "Fake", executable = "fake-agent", args = emptyList(),
            extraction = ResultExtraction.JsonField("result"))
        val found = client.which(tool.executable, null)
        assertTrue(found.path!!.endsWith("/tools/fake-agent"))

        val cwd = File(root, "work dir").apply { mkdirs() }.canonicalPath
        val taskId = Uuid.random().toString()
        val meta = RemoteTaskMeta(name = "Fake agent", kind = TaskKind.AGENT, prompt = "it's a test",
            agent = RemoteTaskMeta.AgentRef(tool.id, tool.name), cwd = cwd, notify = TaskNotify.ALWAYS, createdAt = 1)
        val rendered = RemoteTaskScript.renderAgent(tool, null, "it's a test", cwd, found.path!!, found.pathEnv)
        assertEquals("systemd", client.commit(taskId, meta, rendered, TaskSchedule.Daily(9, 0), 600))

        val listed = client.list().tasks.single()
        assertEquals(meta, RemoteTaskClient.decodeMeta(listed))
        assertEquals(TaskSchedule.Daily(9, 0), TaskSchedule.parseHelper(listed.schedule!!))
        assertTrue(listed.enabled && listed.timerOk)

        client.runNow(taskId)
        val sync = client.sync(null)
        val record = sync.runs.single()
        assertEquals("ok", record.status)
        assertEquals(record.runId, sync.cursor)
        val output = RemoteTaskClient.excerpt(record)
        assertTrue(output.contains("warning: noisy stderr"))
        assertEquals(ExtractedResult("it's a test via http://proxy:7890 in $cwd", null), RemoteTaskResults.extract(output, tool.extraction))
        assertEquals(output, client.log(taskId, record.runId))
        assertTrue(client.sync(sync.cursor).runs.isEmpty())

        client.setEnabled(taskId, false)
        assertFalse(client.list().tasks.single().enabled)
        client.remove(taskId)
        assertTrue(client.list().tasks.isEmpty())
    }

    @Test fun `helper refusals surface their detail`() {
        val client = RemoteTaskClient(session())
        val error = assertThrows(RemoteTaskCommandException::class.java) { client.runNow("missing-task") }
        assertEquals("no such task", error.detail)
        assertThrows(IllegalArgumentException::class.java) {
            client.commit("bad id!", RemoteTaskMeta(name = "x", kind = TaskKind.COMMAND, command = "true", cwd = "/",
                notify = TaskNotify.RECORD, createdAt = 0), RemoteTaskScript.renderCommand("true", "/", null), TaskSchedule.Interval(30), 600)
        }
    }

    @Test fun `windows computers are refused up front`() {
        val ssh = mockk<RemoteWorkspaceSession>()
        every { ssh.platform } returns RemotePlatform.WINDOWS
        assertThrows(RemoteTasksUnsupportedException::class.java) { RemoteTaskClient(ssh) }
    }

    private fun session(): RemoteWorkspaceSession {
        val ssh = mockk<RemoteWorkspaceSession>()
        every { ssh.platform } returns RemotePlatform.POSIX
        every { ssh.execute(any(), any(), any(), any(), any()) } answers {
            val process = ProcessBuilder("/bin/sh", "-c", arg<String>(0)).directory(home).apply {
                environment().clear()
                environment().putAll(env)
            }.start()
            process.outputStream.use { out -> arg<InputStream?>(4)?.copyTo(out) }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            WorkspaceCommandResult(process.exitValue(), stdout, stderr)
        }
        return ssh
    }

    private fun stub(name: String, body: String) {
        File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }
}
