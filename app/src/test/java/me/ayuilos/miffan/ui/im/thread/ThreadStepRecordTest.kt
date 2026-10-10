package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.R
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadStepRecordTest {
    private fun tool(name: String, input: String = "{}", output: String? = """{"ok":true}""", state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool("id-$name-$input", name, input, output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), state)

    @Test
    fun describesStepsInPlainWords() {
        val steps = threadSteps(listOf(
            tool("workspace_write_file", """{"path":"/work/notes/report.md","content":"secret"}"""),
            tool("workspace_shell", """{"command":"rm -rf build"}"""),
            tool("mcp__github__create_issue"),
        ))
        assertEquals(listOf(R.string.im_step_write_file, R.string.im_step_command, R.string.im_step_mcp), steps.map { it.label })
        // Only the file name, never the command or the file content.
        assertEquals(listOf("report.md"), steps[0].args)
        assertEquals(emptyList<String>(), steps[1].args)
        assertEquals(listOf("github", "create_issue"), steps[2].args)
        assertTrue(steps.all { it.changes })
    }

    @Test
    fun leavesOutComputerActionsAndPromptsTheChatAlreadyShows() {
        val steps = threadSteps(listOf(
            tool("computer_click"),
            tool("ask_user", state = ToolApprovalState.Answered("{}")),
            tool("request_web_search"),
            tool("workspace_write_file", state = ToolApprovalState.Pending, output = null),
            tool("workspace_shell", state = ToolApprovalState.Denied("no")),
            UIMessagePart.Text("好的"),
        ))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun recordsChangesOrLongRunsButNotALookupOrTwo() {
        assertFalse(threadSteps(listOf(tool("search_web"), tool("scrape_web"))).worthRecording())
        assertTrue(threadSteps(listOf(tool("search_web"), tool("scrape_web"), tool("search_web"))).worthRecording())
        assertTrue(threadSteps(listOf(tool("memory_tool", """{"action":"create"}"""))).worthRecording())
        assertFalse(threadSteps(listOf(tool("memory_tool", """{"action":"read"}"""))).worthRecording())
    }

    @Test
    fun marksFailedAndRunningSteps() {
        val steps = threadSteps(listOf(
            tool("workspace_shell", output = """{"exitCode":1,"stdout":"","stderr":"no","timedOut":false}"""),
            tool("workspace_shell", output = """{"exitCode":0,"stdout":"","stderr":"","timedOut":true}"""),
            tool("search_web", output = """{"error":"boom"}"""),
            tool("workspace_shell", output = """{"exitCode":0,"stdout":"ok","stderr":"","timedOut":false}"""),
            tool("workspace_read_file", output = null),
        ))
        assertEquals(listOf(true, true, true, false, false), steps.map { it.failed })
        assertEquals(listOf(false, false, false, false, true), steps.map { it.running })
    }
}
