package me.ayuilos.miffan.data.ai.mcp

import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentMcpIsolationTest {
    @Test fun backgroundCallerUsesItsOwnBindingsInsteadOfTheSelectedProfile() {
        fun server(name: String) = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = name, tools = listOf(McpTool(name = "tool"),
                McpTool(name = "disabled", enable = false))),
        )
        val own = server("Own")
        val other = server("Other")
        val disabled = server("Disabled").let { it.copy(commonOptions = it.commonOptions.copy(enable = false)) }
        val a = Assistant(mcpServers = setOf(own.id, disabled.id))
        val b = Assistant(mcpServers = setOf(other.id))
        val settings = Settings(assistantId = b.id, assistants = listOf(a, b), mcpServers = listOf(own, other, disabled))
        for (owner in listOf(false, true)) {
            val tools = availableMcpTools(settings, AgentAccess.forAssistant(a, owner))
            assertEquals(listOf(own.id), tools.map { it.first })
            assertEquals(listOf("Own"), tools.map { it.second })
            assertEquals(listOf("tool"), tools.map { it.third.name })
        }
        assertEquals(listOf(other.id), availableMcpTools(settings, null).map { it.first })
        assertTrue(availableMcpTools(settings, AgentAccess(Uuid.random(), false, "missing")).isEmpty())
    }
}
