package me.ayuilos.miffan.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolTargetGuardTest {
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
