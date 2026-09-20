package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.ai.ui.handleTextGenerationResult
import me.ayuilos.miffan.data.ai.transformers.transformThinkTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class WorkspaceToolTargetGuardTest {
    private val model = Model(modelId = "test-model")
    private val original = WorkspaceToolTargetSnapshot(
        assistantId = "assistant",
        workspacePermissionRevision = "binding-A-1",
        workspaceId = "A",
        scopeId = "assistant",
        kind = "REMOTE",
        remoteHostId = "host",
        remoteRoot = "/srv/project",
        hostConnectionRevision = "host-1",
        workspaceName = "Project",
        remoteHostName = "Server",
        remoteHostLabel = "user@server:22",
    )

    private fun call(target: WorkspaceToolTargetSnapshot? = original, name: String = WORKSPACE_SHELL_TOOL_NAME) =
        UIMessagePart.Tool(toolCallId = "call", toolName = name, input = "{}", workspaceTarget = target)

    private fun definition(target: WorkspaceToolTargetSnapshot = original, name: String = WORKSPACE_SHELL_TOOL_NAME) =
        Tool(name = name, description = "", workspaceTarget = target) { emptyList() }

    @Test
    fun `streamed new call captures request target before completion and survives persistence`() {
        val initial = listOf(UIMessage.user("run a command"))
        val binder = WorkspaceToolTargetBinder(initial, listOf(definition()))
        val stream = StreamChunkHandler(model)
        var messages = binder.bind(stream.handle(initial, StreamChunk.ToolCallStart("new-call")))
        messages = binder.bind(stream.handle(messages, StreamChunk.ToolCallDelta("new-call", "workspace_")))
        assertNull(messages.last().getTools().single().workspaceTarget)

        messages = binder.bind(stream.handle(messages, StreamChunk.ToolCallDelta("new-call", "shell", "{\"command\":")))
        assertEquals(original, messages.last().getTools().single().workspaceTarget)
        assertNull(messages.last().finishedAt)

        messages = binder.bind(stream.handle(messages, StreamChunk.ToolCallDelta("new-call", inputDelta = "\"pwd\"}")))
        messages = binder.bind(stream.handle(messages, StreamChunk.ToolCallEnd("new-call")))
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(messages.last()))
        val restoredCall = restored.getTools().single()
        assertEquals(original, restoredCall.workspaceTarget)
        assertEquals("{\"command\":\"pwd\"}", restoredCall.input)
        assertEquals(listOf(restoredCall), binder.newTools(listOf(restored)))
    }

    @Test
    fun `app binds only new parts when extending an assistant message`() {
        val old = call(target = null).copy(toolCallId = "old-call")
        val initial = listOf(UIMessage.assistant("").copy(parts = listOf(old)))
        val binder = WorkspaceToolTargetBinder(initial, listOf(definition()))
        val stream = StreamChunkHandler(model)
        val messages = binder.bind(stream.handle(initial, StreamChunk.ToolCallStart("new-call", "workspace_shell")))
        assertNull(messages.last().getTools().first().workspaceTarget)
        assertEquals(original, messages.last().getTools().last().workspaceTarget)
        assertEquals(listOf(messages.last().getTools().last()), binder.newTools(messages))
    }

    @Test
    fun `think tag transformation cannot move an old call into the new tool set`() {
        val old = call(target = null).copy(toolCallId = "old-call")
        val initial = listOf(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text("<think>reason</think>"), old,
        )))
        val binder = WorkspaceToolTargetBinder(initial, listOf(definition()))
        val stream = StreamChunkHandler(model)
        val bound = binder.bind(stream.handle(initial, StreamChunk.ToolCallStart("new-call", "workspace_shell")))
        val transformed = bound.transformThinkTags(Clock.System.now(), generationFinished = true)
        assertEquals(4, transformed.last().parts.size)
        assertEquals(setOf(3), binder.newToolPartIndexes(transformed.last()))
        assertEquals("new-call", binder.newTools(transformed).single().toolCallId)
        assertNull(transformed.last().getTools().first().workspaceTarget)
        assertEquals(original, transformed.last().getTools().last().workspaceTarget)
    }

    @Test
    fun `non streaming result binds new tool without changing old call`() {
        val old = call(target = null).copy(toolCallId = "old-call")
        val initial = listOf(UIMessage.assistant("").copy(parts = listOf(old)))
        val result = TextGenerationResult(
            id = "response",
            model = model.modelId,
            message = UIMessage.assistant("").copy(parts = listOf(call(target = null).copy(toolCallId = "new-call"))),
        )
        val binder = WorkspaceToolTargetBinder(initial, listOf(definition()))
        val messages = binder.bind(initial.handleTextGenerationResult(result, model))
        assertNull(messages.last().getTools().first().workspaceTarget)
        assertEquals(original, messages.last().getTools().last().workspaceTarget)
    }

    @Test
    fun `network retry starts a fresh set of tool identities`() {
        val other = original.copy(workspaceId = "B", workspacePermissionRevision = "binding-B")
        val initial = listOf(UIMessage.user("test"))
        val binder = WorkspaceToolTargetBinder(initial, listOf(
            definition(), definition(other, "workspace_read_file"),
        ))
        val first = binder.bind(listOf(UIMessage.assistant("").copy(parts = listOf(call()))))
        assertEquals(original, first.last().getTools().single().workspaceTarget)

        binder.beginAttempt()
        val retry = binder.bind(listOf(UIMessage.assistant("").copy(parts = listOf(
            call(target = null, name = "workspace_read_file")
        ))))
        assertEquals(other, retry.last().getTools().single().workspaceTarget)
    }

    @Test
    fun `provider supplied target is discarded before name is complete`() {
        val forged = original.copy(remoteRoot = "/tmp/forged")
        val binder = WorkspaceToolTargetBinder(listOf(UIMessage.user("test")), listOf(definition()))
        val partial = binder.bind(listOf(UIMessage.assistant("").copy(parts = listOf(
            call(target = forged, name = "workspace_")
        ))))
        assertNull(partial.last().getTools().single().workspaceTarget)
        val complete = binder.bind(listOf(partial.last().copy(parts = listOf(
            call(target = forged)
        ))))
        assertEquals(original, complete.last().getTools().single().workspaceTarget)
    }

    @Test
    fun `restored pending call keeps original target and rejects changed workspace`() = runBlocking {
        val pending = call().copy(approvalState = ToolApprovalState.Pending)
        val restored = Json.decodeFromString<UIMessagePart.Tool>(Json.encodeToString(pending))
        assertEquals(original, restored.workspaceTarget)
        assertDispatchDenied(restored, original.copy(workspaceId = "B", workspacePermissionRevision = "binding-B"))
    }

    private suspend fun assertDispatchDenied(
        saved: UIMessagePart.Tool,
        current: WorkspaceToolTargetSnapshot?,
    ) {
        var executed = 0
        val definition = current?.let {
            Tool(name = saved.toolName, description = "", workspaceTarget = it) {
                executed++
                listOf(UIMessagePart.Text("executed"))
            }
        }
        val failure = runCatching {
            executeToolWithTargetGuard(saved, definition, JsonObject(emptyMap()))
        }.exceptionOrNull()
        assertTrue("A changed target must reject dispatch", failure is IllegalStateException)
        assertEquals(0, executed)
    }

    @Test
    fun `a pending call cannot execute after binding switches to B`() = runBlocking {
        assertDispatchDenied(call(), original.copy(workspaceId = "B", workspacePermissionRevision = "binding-B"))
    }

    @Test
    fun `switching A to B and back still invalidates the old call`() = runBlocking {
        assertDispatchDenied(call(), original.copy(workspacePermissionRevision = "binding-A-2"))
    }

    @Test
    fun `editing connection under the same host id invalidates the old call`() = runBlocking {
        assertDispatchDenied(call(), original.copy(hostConnectionRevision = "host-2"))
    }

    @Test
    fun `disabling then re-enabling shell invalidates the old permission revision`() = runBlocking {
        assertDispatchDenied(call(), original.copy(workspacePermissionRevision = "shell-on-2"))
    }

    @Test
    fun `legacy call without a saved target cannot execute`() = runBlocking {
        assertDispatchDenied(call(target = null), original)
    }

    @Test
    fun `unavailable workspace capability cannot execute a saved call`() = runBlocking {
        assertDispatchDenied(call(), null)
    }

    @Test
    fun `model supplied target is replaced by app captured target`() = runBlocking {
        val forged = original.copy(remoteRoot = "/tmp/forged")
        val captured = captureWorkspaceToolTarget(
            call(target = forged),
            Tool(name = WORKSPACE_SHELL_TOOL_NAME, description = "", workspaceTarget = original) { emptyList() },
        )
        assertEquals(original, captured.workspaceTarget)
        var executed = 0
        val definition = Tool(name = WORKSPACE_SHELL_TOOL_NAME, description = "", workspaceTarget = original) {
            executed++
            listOf(UIMessagePart.Text("ok"))
        }
        executeToolWithTargetGuard(captured, definition, JsonObject(emptyMap()))
        assertEquals(1, executed)
    }

    @Test
    fun `non workspace tools execute without a workspace target`() = runBlocking {
        var executed = 0
        val other = call(target = null, name = "ask_user")
        val definition = Tool(name = "ask_user", description = "") {
            executed++
            listOf(UIMessagePart.Text("ok"))
        }
        assertEquals(other, captureWorkspaceToolTarget(other, definition))
        executeToolWithTargetGuard(other, definition, JsonObject(emptyMap()))
        assertEquals(1, executed)
    }
}
