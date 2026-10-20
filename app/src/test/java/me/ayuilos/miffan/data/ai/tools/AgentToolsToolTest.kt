package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.AgentToolPresets
import me.ayuilos.miffan.data.remotetask.ResultExtraction
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentToolsToolTest {
    private val partner = Uuid.random()
    private val mine = AgentToolDefinition(id = "mine", name = "aider", executable = "aider", args = listOf("--message", "{prompt}"))
    private val current = AgentToolPresets.ALL + mine

    private fun parse(json: String) = parseAgentToolDefinition(Json.parseToJsonElement(json) as JsonObject, current, partner)
    private fun refusal(json: String) = assertThrows(IllegalArgumentException::class.java) { parse(json) }.message!!

    @Test fun `a partner adds a tool and is recorded as its author`() {
        val tool = parse("""{"action":"save","name":"opencode","executable":"opencode","args":["run","{prompt}"],
            "permissions":[{"name":"read_only","args":["--agent","plan"]},{"name":"workspace_write","args":[],"writes":true}],
            "result":{"type":"jsonl","match":{"type":"text"},"field":"part.text"},"version_arg":"--version"}""")
        assertEquals(partner, tool.addedBy)
        assertTrue(tool.takesPromptArgument)
        assertEquals(listOf("read_only", "workspace_write"), tool.permissions.map { it.name })
        assertTrue(tool.permissions[1].writes)
        assertEquals(ResultExtraction.JsonLines(mapOf("type" to "text"), "part.text"), tool.extraction)
        assertFalse(tool.isPreset)
    }

    @Test fun `changing a tool keeps its id and original author`() {
        val userTool = mine.copy(addedBy = null)
        val changed = parseAgentToolDefinition(Json.parseToJsonElement(
            """{"action":"save","tool_id":"mine","name":"aider","executable":"/opt/aider/bin/aider","args":["--yes-always","--message","{prompt}"]}""",
        ) as JsonObject, AgentToolPresets.ALL + userTool, partner)
        assertEquals("mine", changed.id)
        assertNull(changed.addedBy)
        assertEquals(ResultExtraction.Text, changed.extraction)
    }

    @Test fun `presets, duplicates and bad shapes are refused`() {
        assertTrue(refusal("""{"tool_id":"preset:claude-code","name":"Claude Code","executable":"claude","args":[]}""").startsWith("Built-in presets"))
        assertEquals("Another tool is already called Codex CLI", refusal("""{"name":"codex cli","executable":"codex","args":[]}"""))
        assertEquals("No tool with that id", refusal("""{"tool_id":"nope","name":"x","executable":"x","args":[]}"""))
        assertEquals("Use {prompt} at most once", refusal("""{"name":"x","executable":"x","args":["{prompt}","{prompt}"]}"""))
        assertEquals("Invalid variable name", refusal("""{"name":"x","executable":"x","args":[],"extra_env":["NOT-OK"]}"""))
        assertEquals("result.match is required for jsonl", refusal("""{"name":"x","executable":"x","args":[],"result":{"type":"jsonl","field":"a"}}"""))
        assertEquals("args must be a list of strings", refusal("""{"name":"x","executable":"x","args":[1]}"""))
        assertEquals("Permission names must differ",
            refusal("""{"name":"x","executable":"x","args":[],"permissions":[{"name":"a","args":[]},{"name":"a","args":[]}]}"""))
    }
}
