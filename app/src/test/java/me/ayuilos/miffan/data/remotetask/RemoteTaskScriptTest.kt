package me.ayuilos.miffan.data.remotetask

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class RemoteTaskScriptTest {
    @Test fun `schedules map to helper words and back`() {
        val cases = mapOf(
            TaskSchedule.Daily(9, 5) to listOf("daily", "09:05"),
            TaskSchedule.Weekly(setOf(5, 0, 3), 18, 30) to listOf("weekly", "0,3,5", "18:30"),
            TaskSchedule.Interval(30) to listOf("interval", "30"),
        )
        for ((schedule, words) in cases) {
            assertEquals(words, schedule.helperArgs())
            assertEquals(schedule, TaskSchedule.parseHelper(words.joinToString(" ")))
        }
        for (bad in listOf(TaskSchedule.Daily(24, 0), TaskSchedule.Weekly(emptySet(), 9, 0),
            TaskSchedule.Weekly(setOf(7), 9, 0), TaskSchedule.Interval(45))) {
            assertThrows(IllegalArgumentException::class.java) { bad.helperArgs() }
        }
        assertNull(TaskSchedule.parseHelper("daily 9:00"))
        assertNull(TaskSchedule.parseHelper("hourly"))
    }

    @Test fun `next run follows the computer's zone and weekdays`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val monday = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone)
        assertEquals(monday.withHour(9).plusDays(1), TaskSchedule.Daily(9, 0).nextRun(monday))
        assertEquals(monday.withHour(11), TaskSchedule.Daily(11, 0).nextRun(monday))
        // Sunday (0) and Wednesday (3): Wednesday 2026-10-21 comes first.
        assertEquals(ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, zone), TaskSchedule.Weekly(setOf(0, 3), 8, 0).nextRun(monday))
        assertNull(TaskSchedule.Interval(30).nextRun(monday))
        assertEquals(ZoneId.of("Asia/Shanghai"), RemoteTaskProbe("systemd", timezone = "Asia/Shanghai").zone())
        assertEquals(ZoneId.of("+08:00"), RemoteTaskProbe("launchd", timezone = null, utcOffset = "+0800").zone())
    }

    @Test fun `rendered scripts keep every value literal`() {
        val dir = Files.createTempDirectory("task script").toFile()
        try {
            val hostile = "it's \$HOME `id` \"quoted\" \\ ; rm -rf / \n second line"
            val tool = AgentToolDefinition(
                name = "echo agent", executable = "/bin/echo",
                args = listOf("--prompt", AgentToolDefinition.PROMPT_PLACEHOLDER),
                permissions = listOf(AgentToolDefinition.Permission("read", listOf("--mode", "read only"))),
            )
            val agent = RemoteTaskScript.renderAgent(tool, "read", hostile, dir.absolutePath, "/bin/echo", "/usr/bin:/bin")
            assertEquals("", agent.prompt)
            assertEquals("--prompt $hostile --mode read only\n", sh(agent.run, dir))

            val stdinTool = tool.copy(args = listOf("-u"), permissions = emptyList())
            val viaStdin = RemoteTaskScript.renderAgent(stdinTool, null, hostile, dir.absolutePath, "/bin/cat", null)
            assertEquals(hostile, viaStdin.prompt)
            assertEquals(hostile, sh(viaStdin.run, dir, stdin = viaStdin.prompt))

            // A command task is the user's own shell; it still runs in the chosen directory.
            val command = RemoteTaskScript.renderCommand("pwd -P; printf '%s' \"\$FOO\"", dir.absolutePath, null)
            assertEquals(dir.canonicalPath + "\nbar", sh(command.run, File("/"), env = mapOf("FOO" to "bar")))
            assertEquals(DEFAULT_TASK_ENV.joinToString("") { "$it\n" }, command.env)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `fallback PATH applies only without the login shell environment`() {
        val dir = Files.createTempDirectory("task path").toFile()
        try {
            val rendered = RemoteTaskScript.renderCommand("printf '%s' \"\$PATH\"", dir.absolutePath, "/fallback/bin")
            assertEquals("/fallback/bin", sh(rendered.run, dir, env = mapOf("PATH" to "/login/bin")))
            assertEquals("/login/bin", sh(rendered.run, dir, env = mapOf("PATH" to "/login/bin", "MIFFAN_LOGIN_ENV" to "ok")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `agent command lines are validated`() {
        val tool = AgentToolPresets.CLAUDE_CODE
        assertEquals(listOf("/usr/bin/claude", "-p", "--output-format", "json", "--no-session-persistence",
            "--permission-mode", "dontAsk"), RemoteTaskScript.agentArgv(tool, AgentToolPresets.READ_ONLY, "/usr/bin/claude", "hi"))
        assertThrows(IllegalArgumentException::class.java) { RemoteTaskScript.agentArgv(tool, null, "/usr/bin/claude", "hi") }
        assertThrows(IllegalArgumentException::class.java) { RemoteTaskScript.agentArgv(tool, "root", "/usr/bin/claude", "hi") }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteTaskScript.renderAgent(tool, AgentToolPresets.READ_ONLY, "hi", "/tmp", "claude", null)
        }
        val yolo = AgentToolDefinition(name = "yolo", executable = "claude", args = listOf("-p", "--permission-mode", "bypassPermissions"))
        assertTrue(RemoteTaskScript.grantsFullAccess(RemoteTaskScript.agentArgv(yolo, null, "/c", "x")))
        assertFalse(RemoteTaskScript.grantsFullAccess(RemoteTaskScript.agentArgv(tool, AgentToolPresets.WORKSPACE_WRITE, "/c", "x")))
        AgentToolPresets.ALL.forEach { it.validate() }
        assertThrows(IllegalArgumentException::class.java) { tool.copy(extraEnv = listOf("BAD-NAME")).validate() }
    }

    @Test fun `results come out of real agent output with stderr noise`() {
        // Claude Code 2.1.304 under launchd without the proxy (P1): exit 1, is_error true.
        val claudeFailure = """
            some warning on stderr
            {"type":"result","subtype":"success","is_error":true,"api_error_status":403,"result":"Failed to authenticate. API Error: 403 Request not allowed"}
        """.trimIndent()
        assertEquals(ExtractedResult(null, "Failed to authenticate. API Error: 403 Request not allowed"),
            RemoteTaskResults.extract(claudeFailure, AgentToolPresets.CLAUDE_CODE.extraction))
        val claudeOk = """{"type":"result","is_error":false,"result":"miffan-ok","total_cost_usd":0.11}"""
        assertEquals(ExtractedResult("miffan-ok", null), RemoteTaskResults.extract(claudeOk, AgentToolPresets.CLAUDE_CODE.extraction))

        // Codex 0.160.0 on CachyOS (P1): MCP errors interleaved, then turn.failed.
        val codex = """
            {"type":"turn.started"}
            2026-10-20T05:10:31Z ERROR codex_rmcp_client::oauth: invalid_grant
            {"type":"item.completed","item":{"id":"r","type":"reasoning","text":"thinking"}}
            {"type":"item.completed","item":{"id":"m","type":"agent_message","text":"done"}}
            {"type":"turn.failed","error":{"message":"model not supported"}}
        """.trimIndent()
        assertEquals(ExtractedResult("done", "model not supported"), RemoteTaskResults.extract(codex, AgentToolPresets.CODEX.extraction))
        assertEquals(ExtractedResult(null, null), RemoteTaskResults.extract("not json", AgentToolPresets.CLAUDE_CODE.extraction))
        assertEquals(ExtractedResult("plain", null), RemoteTaskResults.extract("\nplain\n", ResultExtraction.Text))
    }

    @Test fun `sync cursor rewinds two minutes`() {
        assertEquals("20261020T050834Z-0", RemoteTaskRepository.rewind("20261020T051034Z-1600339"))
        assertEquals("20261019T235900Z-0", RemoteTaskRepository.rewind("20261020T000100Z-7"))
        assertEquals("garbage", RemoteTaskRepository.rewind("garbage"))
    }

    private fun sh(script: String, dir: File, stdin: String = "", env: Map<String, String> = emptyMap()): String {
        val file = File.createTempFile("run", ".sh").apply { writeText(script) }
        val process = ProcessBuilder("/bin/sh", file.absolutePath).directory(dir).apply {
            environment().putAll(env)
        }.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
        file.delete()
        return output
    }
}
