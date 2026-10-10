package me.ayuilos.miffan.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.computer.RemoteComputerControl
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.ai.computer.sessionEnded
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerSessionTest {
    private fun driverTool(name: String, vararg properties: String) = JsonInstant.decodeFromString<McpTool>(
        """{"name":"$name","inputSchema":{"type":"object","properties":{${properties.joinToString(",") { "\"$it\":{}" }}}}}""",
    )
    private val typeText = driverTool("type_text", "text", "x", "y", "scope", "target", "pid", "window_id", "element_token", "delay_ms", "session")
    private val click = driverTool("click", "x", "y", "scope", "target", "pid", "window_id", "session")

    @Test
    fun recognisesTheDriverRefusingAnEndedSession() {
        val ended = CallToolResult(content = listOf(TextContent(
            "session 'miffan-2fa4d8d5' has ended; call start_session with session='miffan-2fa4d8d5' to start it again",
        )), isError = true)
        assertTrue(ended.sessionEnded())
        assertTrue(CallToolResult(content = emptyList(), isError = true,
            structuredContent = buildJsonObject { put("code", "session_ended") }).sessionEnded())
        assertFalse(CallToolResult(content = listOf(TextContent("element not found")), isError = true).sessionEnded())
        assertFalse(CallToolResult(content = listOf(TextContent("session has ended; call start_session")), isError = false).sessionEnded())
    }

    @Test
    fun desktopTypingAtAPositionClicksThereFirst() {
        val args = buildJsonObject { put("scope", "desktop"); put("x", 640); put("y", 120); put("text", "无职转生"); put("delay_ms", 30) }
        assertEquals(buildJsonObject { put("scope", "desktop"); put("x", 640); put("y", 120) }, desktopTypeFocusClick(typeText, args, click))

        val desktopTarget = buildJsonObject {
            put("target", buildJsonObject { put("kind", "desktop"); put("display_id", "primary") }); put("x", 1); put("y", 2); put("text", "a")
        }
        assertTrue(desktopTypeFocusClick(typeText, desktopTarget, click) != null)
        // The window form already clicks before typing, and a token or a missing position needs no help.
        assertNull(desktopTypeFocusClick(typeText, buildJsonObject { put("pid", 7); put("window_id", 9); put("x", 1); put("y", 2); put("text", "a") }, click))
        assertNull(desktopTypeFocusClick(typeText, buildJsonObject { put("scope", "desktop"); put("text", "a") }, click))
        assertNull(desktopTypeFocusClick(typeText, args, null))
    }

    @Test
    fun desktopTypeTextRunsAsClickThenType() = runTest {
        val registry = mockk<RemoteComputerRegistry>()
        val snapshot = WorkspaceToolTargetSnapshot("partner", "revision", "workspace", null, "remote", workspaceName = "Home")
        coEvery { registry.capabilities("workspace") } returns RemoteComputerRegistry.Capabilities("host", listOf(typeText, click), null, emptyList())
        coEvery { registry.call("workspace", "click", any()) } returns CallToolResult(content = listOf(TextContent("Clicked")))
        coEvery { registry.call("workspace", "type_text", any()) } returns CallToolResult(content = listOf(TextContent("Typed text")))
        val tools = createComputerTools(snapshot, false, registry, RemoteComputerControl(), mockk<FilesManager>())

        val result = tools.single { it.name == "computer_type_text" }
            .execute(buildJsonObject { put("scope", "desktop"); put("x", 640); put("y", 120); put("text", "无职转生") })

        coVerifyOrder {
            registry.call("workspace", "click", buildJsonObject { put("scope", "desktop"); put("x", 640); put("y", 120) })
            registry.call("workspace", "type_text", buildJsonObject { put("scope", "desktop"); put("text", "无职转生") })
        }
        assertTrue((result.first() as UIMessagePart.Text).text.contains("Clicked x,y"))
    }

    @Test
    fun aFailedFocusClickTypesNothing() = runTest {
        val registry = mockk<RemoteComputerRegistry>()
        val snapshot = WorkspaceToolTargetSnapshot("partner", "revision", "workspace", null, "remote", workspaceName = "Home")
        coEvery { registry.capabilities("workspace") } returns RemoteComputerRegistry.Capabilities("host", listOf(typeText, click), null, emptyList())
        coEvery { registry.call("workspace", "click", any()) } returns CallToolResult(content = listOf(TextContent("no display")), isError = true)
        val tools = createComputerTools(snapshot, false, registry, RemoteComputerControl(), mockk<FilesManager>())

        val result = tools.single { it.name == "computer_type_text" }
            .execute(buildJsonObject { put("scope", "desktop"); put("x", 1); put("y", 2); put("text", "a") })

        assertTrue((result.first() as UIMessagePart.Text).text.contains("nothing was typed"))
        io.mockk.coVerify(exactly = 0) { registry.call("workspace", "type_text", any()) }
    }
}
