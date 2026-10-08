package me.ayuilos.miffan.data.ai.tools

import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.RemoteComputerControl
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.ui.components.message.ComputerToolStatus
import me.ayuilos.miffan.ui.components.message.computerToolStatus
import me.ayuilos.miffan.ui.im.thread.threadComputerEvidence
import me.ayuilos.miffan.utils.JsonInstant
import me.ayuilos.miffan.utils.computerActionTitle
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolDecisionVia
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.*
import org.junit.Test

class ComputerCloseAppTest {
    private val files = mockk<FilesManager>()
    private fun result(code: String? = null, effect: String? = null, error: Boolean = true) = CallToolResult(
        content = listOf(TextContent("Check list_windows to confirm the result.")),
        structuredContent = buildJsonObject {
            code?.let { put("code", it) }
            effect?.let { put("effect", it) }
        },
        isError = error,
    )

    @Test fun conversionMarksUnconfirmedTimeoutsAndKeepsDetails() = runTest {
        for ((code, effect) in listOf(
            "launch_handoff_timeout" to "refused",
            "launch_handoff_timeout" to "failed", // explicit special case in the protocol
            "window_timeout" to "refused",
            "window_timeout" to null,
        )) {
            val parts = convert(result(code, effect), files)
            assertEquals("""{"status":"unconfirmed"}""", (parts.first() as UIMessagePart.Text).text)
            assertEquals(ComputerToolStatus.UNCONFIRMED, computerToolStatus(parts))
            assertTrue(parts.filterIsInstance<UIMessagePart.Text>().any { it.text.startsWith("structured:") && it.text.contains(code) })
            assertTrue(parts.contains(UIMessagePart.Text("Check list_windows to confirm the result.")))
        }
    }

    @Test fun conversionKeepsDefiniteAndUnstructuredErrorsAsErrors() = runTest {
        for (input in listOf(
            result("window_timeout", "failed"), result("permission_denied", "refused"), result(),
            CallToolResult(content = emptyList(), isError = true),
            CallToolResult(content = emptyList(), isError = true, structuredContent = buildJsonObject { put("code", JsonObject(emptyMap())) }),
        )) {
            assertEquals(ComputerToolStatus.ERROR, computerToolStatus(convert(input, files)))
        }
        assertNull(computerToolStatus(convert(result("launch_handoff_timeout", "refused", error = false), files)))
    }

    @Test fun errorWinsOverUnconfirmedAndQuotedExamplesDoNotCount() {
        val unconfirmed = UIMessagePart.Text("""{"status":"unconfirmed"}""")
        val error = UIMessagePart.Text("""structured: {"status":"error"}""")
        assertEquals(ComputerToolStatus.UNCONFIRMED, computerToolStatus(listOf(unconfirmed)))
        assertEquals(ComputerToolStatus.UNCONFIRMED, computerToolStatus(listOf(UIMessagePart.Text("""structured: {"status":"unconfirmed"}"""))))
        assertEquals(ComputerToolStatus.ERROR, computerToolStatus(listOf(unconfirmed, error)))
        assertEquals(ComputerToolStatus.ERROR, computerToolStatus(listOf(error, unconfirmed)))
        assertNull(computerToolStatus(listOf(UIMessagePart.Text("Example: {\"status\":\"unconfirmed\"}"))))
    }

    @Test fun unconfirmedEvidenceIsNeitherFailedNorConfirmedSuccess() {
        val tool = UIMessagePart.Tool("call", "computer_kill_app", """{"pid":42}""",
            output = listOf(UIMessagePart.Text("""{"status":"unconfirmed"}""")))
        val evidence = requireNotNull(threadComputerEvidence(listOf(tool)))
        assertEquals(ComputerToolStatus.UNCONFIRMED, evidence.status)
        assertEquals(0, evidence.actionCount)
        val success = tool.copy(toolCallId = "success", output = listOf(UIMessagePart.Text("done")))
        assertEquals(1, threadComputerEvidence(listOf(tool, success))?.actionCount)
        val failure = tool.copy(toolCallId = "failed", output = listOf(UIMessagePart.Text("""{"status":"error"}""")))
        assertEquals(ComputerToolStatus.ERROR, threadComputerEvidence(listOf(tool, failure))?.status)
    }

    @Test fun closeAppTitleUsesDriverPidAndOptionalAppLabels() {
        for ((input, detail) in listOf(
            """{"pid":188639}""" to "188639",
            """{"name":"Terminal"}""" to "Terminal",
            """{"app":"Steam"}""" to "Steam",
            """{"bundle_id":"com.apple.Terminal"}""" to "com.apple.Terminal",
            "{}" to null,
            "[]" to null,
        )) {
            val title = computerActionTitle("computer_kill_app", JsonInstant.parseToJsonElement(input))
            assertEquals(R.string.computer_use_close_app, title.label)
            assertEquals(detail, title.detail)
        }
    }

    @Test fun closeToolUsesCapabilityFilterApprovalPolicyAndOperatingGuidance() = runTest {
        val registry = mockk<RemoteComputerRegistry>()
        val driverTool = JsonInstant.decodeFromString<McpTool>("""{
            "name":"kill_app","description":"Force-terminate a process by pid.",
            "inputSchema":{"type":"object","properties":{"pid":{"type":"integer"},"session":{"type":"string"}},"required":["pid"]}
        }""")
        val snapshot = WorkspaceToolTargetSnapshot("partner", "revision", "workspace", null, "remote", workspaceName = "Home")
        coEvery { registry.capabilities("workspace") } returns RemoteComputerRegistry.Capabilities("host", listOf(driverTool), null, emptyList())
        val args = buildJsonObject { put("pid", 42) }
        for (ask in listOf(true, false)) {
            val tools = createComputerTools(snapshot, ask, registry, RemoteComputerControl(), files)
            val kill = tools.single { it.name == "computer_kill_app" }
            assertEquals(ask, kill.needsApproval(args))
            assertEquals(if (ask) null else ToolDecisionVia.NO_ASK_SETTING, kill.autoApprovedBy(args))
            val schema = kill.parameters() as me.rerere.ai.core.InputSchema.Obj
            assertEquals(listOf("pid"), schema.required)
            assertFalse(schema.properties.containsKey("session"))
            val prompt = tools.single { it.name == "computer_read_guide" }.systemPrompt(Model(), emptyList())
            assertTrue(prompt.contains("launch_app only opens GUI apps; never use it to run commands such as kill."))
            assertTrue(prompt.contains("For other commands use the shell tool if you have one."))
        }
        coEvery { registry.capabilities("workspace") } returns RemoteComputerRegistry.Capabilities("host", emptyList(), null, emptyList())
        assertFalse(createComputerTools(snapshot, true, registry, RemoteComputerControl(), files).any { it.name == "computer_kill_app" })
        val entryPrompt = ComputerToolbox(snapshot, true) { emptyList() }.tools().single().systemPrompt(Model(), emptyList())
        assertFalse(entryPrompt.contains("launch_app only opens"))
    }
}
