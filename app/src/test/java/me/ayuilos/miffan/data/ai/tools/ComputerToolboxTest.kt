package me.ayuilos.miffan.data.ai.tools

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerToolboxTest {
    @Test fun windowsClipboardUsesActionApprovalAndPlatformGuidance() {
        assertTrue("clipboard_write" in COMPUTER_DRIVER_TOOL_NAMES)
        assertNull(computerAutoApprovedBy("clipboard_write", JsonObject(emptyMap()), true))
        assertEquals(me.rerere.ai.ui.ToolDecisionVia.NO_ASK_SETTING, computerAutoApprovedBy("clipboard_write", JsonObject(emptyMap()), false))
        assertTrue(WINDOWS_COMPUTER_GUIDANCE.contains("Ctrl+V"))
        assertTrue(WINDOWS_COMPUTER_GUIDANCE.contains("historical documents"))
        assertTrue(WINDOWS_COMPUTER_GUIDANCE.contains("UAC"))
    }

    private val target = WorkspaceToolTargetSnapshot(
        assistantId = "assistant", workspacePermissionRevision = "permission", workspaceId = "workspace",
        scopeId = null, kind = "REMOTE", workspaceName = "Home", remoteHostName = "cachyos",
    )
    private val click = Tool(name = "computer_click", description = "", execute = { emptyList() })

    @Test
    fun offersOnlyTheEntryUntilThePartnerStarts() = runTest {
        var loads = 0
        var sessions = 0
        val toolbox = ComputerToolbox(target, approvalRequired = true, ensureSession = { sessions++; "miffan-1" }) { loads++; listOf(click) }
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
        // Every start checks the session again, so a session the driver ended comes back.
        assertEquals(2, sessions)
    }

    @Test
    fun startIsNotReadyWhenTheSessionCannotStart() = runTest {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        val toolbox = ComputerToolbox(target, approvalRequired = true, ensureSession = { error("session refused") }) { listOf(click) }

        val text = (toolbox.tools().single().execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text

        assertTrue(text.contains("\"error\""))
        assertTrue(text.contains("session refused"))
    }

    @Test
    fun conversationsThatTouchedTheComputerNeedTheFullTools() {
        val plain = UIMessage.assistant("hi")
        val started = plain.copy(parts = listOf(UIMessagePart.Tool(toolCallId = "1", toolName = COMPUTER_START_TOOL, input = "{}")))
        assertFalse(listOf(plain).usesComputer())
        assertTrue(listOf(plain, started).usesComputer())
    }
}
