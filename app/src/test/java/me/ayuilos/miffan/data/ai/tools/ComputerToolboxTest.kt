package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerToolboxTest {
    private val target = WorkspaceToolTargetSnapshot(
        assistantId = "assistant", workspacePermissionRevision = "permission", workspaceId = "workspace",
        scopeId = null, kind = "REMOTE", workspaceName = "Home", remoteHostName = "cachyos",
    )
    private val click = Tool(name = "computer_click", description = "", execute = { emptyList() })

    @Test
    fun offersOnlyTheEntryUntilThePartnerStarts() = runTest {
        var loads = 0
        val toolbox = ComputerToolbox(target, approvalRequired = true) { loads++; listOf(click) }
        assertEquals(listOf(COMPUTER_START_TOOL), toolbox.tools().map { it.name })
        assertTrue(toolbox.tools().single().systemPrompt(Model(), emptyList()).contains("cachyos"))
        assertEquals(0, loads)

        val result = toolbox.tools().single().execute(JsonObject(emptyMap()))

        assertTrue((result.single() as UIMessagePart.Text).text.contains("\"ready\""))
        assertEquals(listOf(COMPUTER_START_TOOL, "computer_click"), toolbox.tools().map { it.name })
        // The loaded tools carry their own guide, so the entry stops describing the computer.
        assertEquals("", toolbox.tools().first().systemPrompt(Model(), emptyList()))
        toolbox.tools().first().execute(JsonObject(emptyMap()))
        assertEquals(1, loads)
    }

    @Test
    fun conversationsThatTouchedTheComputerNeedTheFullTools() {
        val plain = UIMessage.assistant("hi")
        val started = plain.copy(parts = listOf(UIMessagePart.Tool(toolCallId = "1", toolName = COMPUTER_START_TOOL, input = "{}")))
        assertFalse(listOf(plain).usesComputer())
        assertTrue(listOf(plain, started).usesComputer())
    }
}
