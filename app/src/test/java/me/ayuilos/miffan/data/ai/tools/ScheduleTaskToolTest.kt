package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.AgentToolPresets
import me.ayuilos.miffan.data.remotetask.RemoteTaskMeta
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.data.remotetask.TaskNotify
import me.ayuilos.miffan.data.remotetask.TaskSchedule
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ScheduleTaskToolTest {
    private val owner = RemoteTaskMeta.Owner(Uuid.random(), Uuid.random())
    private val tools = AgentToolPresets.ALL + AgentToolDefinition(id = "mine", name = "My aider", executable = "aider", args = listOf("--message", "{prompt}"))

    private fun parse(json: String, update: Boolean = false) =
        parseScheduleTaskDraft(Json.parseToJsonElement(json) as JsonObject, tools, owner, update)

    @Test fun `command task gets command defaults`() {
        val draft = parse("""{"action":"create","name":"Backup","kind":"command","command":"rsync -a ~/a ~/b",
            "schedule":{"type":"weekly","weekdays":[1,5],"time":"7:05"}}""")
        assertEquals(TaskKind.COMMAND, draft.kind)
        assertEquals(TaskSchedule.Weekly(setOf(1, 5), 7, 5), draft.schedule)
        assertEquals(600, draft.timeoutSeconds)
        assertEquals(TaskNotify.FAILURE, draft.notify)
        assertEquals(owner, draft.owner)
        assertNull(draft.taskId)
    }

    @Test fun `agent task picks a read-only level unless told otherwise`() {
        val draft = parse("""{"action":"create","name":"Tidy downloads","kind":"agent","agent_tool":"claude code",
            "prompt":"Sort ~/Downloads","schedule":{"type":"daily","time":"09:00"}}""")
        assertEquals(AgentToolPresets.CLAUDE_CODE.id, draft.agentToolId)
        assertEquals(AgentToolPresets.READ_ONLY, draft.permission)
        assertEquals(1800, draft.timeoutSeconds)
        assertEquals(TaskNotify.ALWAYS, draft.notify)
        val custom = parse("""{"action":"create","name":"x","kind":"agent","agent_tool":"My aider","prompt":"p",
            "schedule":{"type":"interval","minutes":60}}""")
        assertNull(custom.permission)
    }

    @Test fun `invalid arguments explain what to fix`() {
        fun message(json: String, update: Boolean = false) =
            assertThrows(IllegalArgumentException::class.java) { parse(json, update) }.message!!
        assertTrue(message("""{"name":"x","kind":"agent","agent_tool":"Cursor","prompt":"p","schedule":{"type":"daily","time":"09:00"}}""")
            .contains("Available: Claude Code"))
        assertTrue(message("""{"name":"x","kind":"agent","agent_tool":"Codex CLI","permission":"root","prompt":"p",
            "schedule":{"type":"daily","time":"09:00"}}""").startsWith("permission must be one of"))
        assertEquals("schedule.minutes is required", message("""{"name":"x","kind":"command","command":"true","schedule":{"type":"interval"}}"""))
        assertTrue(message("""{"name":"x","kind":"command","command":"true","schedule":{"type":"interval","minutes":45}}""").contains("Interval"))
        assertEquals("schedule.time must be HH:MM in 24-hour time",
            message("""{"name":"x","kind":"command","command":"true","schedule":{"type":"daily","time":"9pm"}}"""))
        assertEquals("command is required for a command task", message("""{"name":"x","kind":"command","schedule":{"type":"daily","time":"09:00"}}"""))
        assertEquals("task_id is required", message("""{"name":"x","kind":"command","command":"true","schedule":{"type":"daily","time":"09:00"}}""", update = true))
        assertEquals("kind must be command or agent", message("""{"name":"x","kind":"script","schedule":{"type":"daily","time":"09:00"}}"""))
    }
}
