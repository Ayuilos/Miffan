package me.ayuilos.miffan.data.ai.tools

import android.util.Log
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.GenerationChunk
import me.ayuilos.miffan.data.ai.GenerationHandler
import me.ayuilos.miffan.data.ai.OWNER_THREAD_RELAY_APPROVAL_REASON
import me.ayuilos.miffan.data.ai.withAgentRelayApproval
import me.ayuilos.miffan.data.audit.recordCardDecision
import me.ayuilos.miffan.data.audit.waitForApproval
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageSetupApprovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val chatModel = Model(modelId = "chat", abilities = listOf(ModelAbility.TOOL))
    private val imageModel = Model(modelId = "draw", displayName = "Drawing model", outputModalities = listOf(Modality.IMAGE))
    private val providerSetting = ProviderSetting.OpenAI(models = listOf(chatModel, imageModel))
    private var current = Settings(providers = listOf(providerSetting))
    private val imageProvider = mockk<Provider<ProviderSetting>>()
    private val chatProvider = mockk<Provider<ProviderSetting.OpenAI>>()
    private val args = buildJsonObject { put("prompt", "Draw a bowl") }
    private val originalCall = UIMessagePart.Tool("image-call", GENERATE_IMAGE_TOOL, args.toString(),
        metadata = buildJsonObject { put("provider_field", "signature") })

    @Before fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
    }
    @After fun restoreAndroidLog() = unmockkStatic(Log::class)

    private fun tool() = createImageGenerationTool(
        settings = { current }, messages = { emptyList() }, provider = { imageProvider },
        saveImage = { bytes -> temporary.newFile().apply { writeBytes(bytes) }.toURI().toString() },
        downloadImage = { error("Unexpected download") },
    )

    private fun handler(): GenerationHandler {
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(providerSetting) } returns chatProvider
        return GenerationHandler(mockk(), manager, JsonInstant, mockk(), mockk(), mockk())
    }

    private fun lastMessages(chunks: List<GenerationChunk>) = (chunks.last() as GenerationChunk.Messages).messages

    @Test fun setupAlwaysPausesEvenWithComputerAutomaticModeAndShellNoAsk() = runTest {
        val imageTool = tool()
        val response = UIMessage.assistant("").copy(parts = listOf(originalCall))
        coEvery { chatProvider.generateText(providerSetting, any(), any()) } returns TextGenerationResult("reply", "chat", response)
        for (mode in ComputerUseMode.entries) {
            val assistant = Assistant(streamOutput = false, computerUse = mode, workspaceShellApprovalRequired = false)
            val chunks = handler().generateText(current, chatModel, listOf(UIMessage.user("Draw a bowl")),
                assistant = assistant, access = AgentAccess.forAssistant(assistant, true),
                tools = listOf(imageTool), maxSteps = 1).toList()
            val pending = lastMessages(chunks).last().getTools().single()
            assertEquals(ToolApprovalState.Pending, pending.approvalState)
            assertEquals(IMAGE_SETUP_APPROVAL_REASON, pending.metadata!!["approval_reason"]!!.jsonPrimitive.content)
            assertEquals("signature", pending.metadata!!["provider_field"]!!.jsonPrimitive.content)
            assertTrue(pending.output.isEmpty())
            assertNotNull(pending.approvalRecord?.requestedAt)
            assertNull(pending.approvalRecord?.decision)
            assertNull(imageTool.autoApprovedBy(args))
        }
        coVerify(exactly = 0) { imageProvider.generateText(any(), any(), any()) }
    }

    @Test fun configuredApprovalResumesAndDrawsInTheSameAssistantMessageAndCall() = runTest {
        val imageTool = tool()
        val pending = originalCall.waitForApproval(123, imageTool.approvalReason)
        val approved = pending.recordCardDecision(ToolApprovalState.Approved, ToolDecisionVia.CARD, 456)
        val assistantMessage = UIMessage.assistant("").copy(parts = listOf(approved))
        current = current.copy(imageToolModelId = imageModel.id)
        coEvery { imageProvider.generateText(providerSetting, any(), any()) } returns TextGenerationResult("image", "draw",
            UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Image("data:image/png;base64,AQID"))))
        val assistant = Assistant(streamOutput = false)
        val result = lastMessages(handler().generateText(current, chatModel,
            listOf(UIMessage.user("Draw a bowl"), assistantMessage), assistant = assistant,
            access = AgentAccess.forAssistant(assistant, true), tools = listOf(imageTool), maxSteps = 1).toList())
        assertEquals(2, result.size)
        assertEquals(assistantMessage.id, result.last().id)
        val executed = result.last().getTools().single()
        assertEquals(originalCall.toolCallId, executed.toolCallId)
        assertEquals(ToolApprovalState.Approved, executed.approvalState)
        assertEquals(ToolDecision.ALLOWED, executed.approvalRecord?.decision)
        assertTrue(executed.output.filterIsInstance<UIMessagePart.Image>().single().url.startsWith("file:"))
        coVerify(exactly = 1) { imageProvider.generateText(providerSetting, any(), any()) }
        coVerify(exactly = 0) { chatProvider.generateText(any(), any(), any()) }
    }

    @Test fun declinedSetupDoesNotExecuteAndNormalDenialReachesTheNextModelRequest() = runTest {
        val imageTool = tool()
        val denied = originalCall.waitForApproval(123, imageTool.approvalReason)
            .recordCardDecision(ToolApprovalState.Denied("先不用"), ToolDecisionVia.CARD, 456)
        val assistantMessage = UIMessage.assistant("").copy(parts = listOf(denied))
        val requested = slot<List<UIMessage>>()
        coEvery { chatProvider.generateText(providerSetting, capture(requested), any()) } returns
            TextGenerationResult("reply", "chat", UIMessage.assistant("Okay."))
        val assistant = Assistant(streamOutput = false)
        val chunks = handler().generateText(current, chatModel,
            listOf(UIMessage.user("Draw a bowl"), assistantMessage), assistant = assistant,
            access = AgentAccess.forAssistant(assistant, true), tools = listOf(imageTool), maxSteps = 2).toList()
        val resumed = (chunks.first() as GenerationChunk.Messages).messages.last()
        assertEquals(assistantMessage.id, resumed.id)
        val executed = resumed.getTools().single()
        assertEquals(originalCall.toolCallId, executed.toolCallId)
        assertEquals(ToolDecision.DECLINED, executed.approvalRecord?.decision)
        val json = Json.parseToJsonElement((executed.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("Tool execution denied by user. Reason: 先不用", json["error"]!!.jsonPrimitive.content)
        val agentCall = requested.captured.flatMap { it.getTools() }.single()
        assertEquals(executed.output, agentCall.output)
        coVerify(exactly = 0) { imageProvider.generateText(any(), any(), any()) }
    }

    @Test fun approvalWithoutConfigurationRetainsSetupFallback() = runTest {
        val imageTool = tool()
        val approved = originalCall.waitForApproval(123, imageTool.approvalReason)
            .recordCardDecision(ToolApprovalState.Approved, ToolDecisionVia.CARD, 456)
        val assistant = Assistant(streamOutput = false)
        val result = lastMessages(handler().generateText(current, chatModel,
            listOf(UIMessage.user("Draw a bowl"), UIMessage.assistant("").copy(parts = listOf(approved))),
            assistant = assistant, access = AgentAccess.forAssistant(assistant, true),
            tools = listOf(imageTool), maxSteps = 1).toList())
        val output = result.last().getTools().single().output
        assertEquals(1, output.size)
        val json = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(true, json["setup_required"]!!.jsonPrimitive.boolean)
        assertFalse("error" in json)
    }

    @Test fun relayGateKeepsItsOwnApprovalReasonForConfiguredAndUnconfiguredTools() {
        val assistant = Assistant()
        val relay = AgentAccess.forAssistant(assistant, false).copy(ownerThreadNonOwnerTurn = true)
        for (configured in listOf(false, true)) {
            current = current.copy(imageToolModelId = imageModel.id.takeIf { configured })
            val wrapped = listOf(tool()).withAgentRelayApproval(relay).single()
            assertTrue(wrapped.needsApproval(args))
            assertEquals(OWNER_THREAD_RELAY_APPROVAL_REASON, wrapped.approvalReason)
            assertNull(wrapped.autoApprovedBy(args))
        }
    }
}
