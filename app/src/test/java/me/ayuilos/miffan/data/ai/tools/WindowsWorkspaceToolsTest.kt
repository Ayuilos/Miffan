package me.ayuilos.miffan.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Test

class WindowsWorkspaceToolsTest {
    @Test fun windowsShellDescribesPowerShellAndDispatchesSingleExecutionWithSeparateStreams() = runBlocking {
        val repository = mockk<WorkspaceRepository>()
        val root = "/C:/Users/Ekk/中文 空格"
        val workspace = WorkspaceEntity("ws", "Windows", "remote:ws", createdAt = 1, updatedAt = 1,
            kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = root,
            shellStatus = WorkspaceShellStatus.READY.name)
        val host = RemoteHostEntity("host", "Windows", "host", 22, "user", "PASSWORD",
            trustedHostKeySha256 = "SHA256:" + "A".repeat(43), createdAt = 1, updatedAt = 1, platform = "windows")
        val target = WorkspaceToolTargetSnapshot("assistant", "permission", "ws", null, "REMOTE",
            remoteHostId = "host", remoteRoot = root, hostConnectionRevision = "legacy", workspaceName = "Windows")
        coEvery { repository.getById("ws") } returns workspace
        coEvery { repository.getHostById("host") } returns host
        coEvery { repository.currentWorkspaceToolTarget("assistant", "ws", null) } returns target
        coEvery { repository.executeCommand("ws", any(), any(), any(), any(), any(), any()) } returns
            WorkspaceCommandResult(7, "中文 stdout", "中文 stderr")
        val tools = createWorkspaceTools("assistant", "ws", null, true, true, null, repository, conversationId = "chat")
        assertFalse(tools.any { it.name == WORKSPACE_TERMINAL_TOOL_NAME })
        val shell = tools.single { it.name == WORKSPACE_SHELL_TOOL_NAME }
        assertTrue(shell.description.contains("Windows PowerShell 5.1"))
        assertTrue(shell.description.contains("C:\\Users\\Ekk\\中文 空格"))
        assertFalse(shell.description.contains("POSIX"))
        val parts = shell.execute(buildJsonObject { put("command", "Write-Output '中文'") })
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("7", result.getValue("exitCode").jsonPrimitive.content)
        assertEquals("中文 stdout", result.getValue("stdout").jsonPrimitive.content)
        assertEquals("中文 stderr", result.getValue("stderr").jsonPrimitive.content)
        assertEquals("false", result.getValue("statePersists").jsonPrimitive.content)
        assertEquals("separate_stdout_stderr", result.getValue("outputType").jsonPrimitive.content)
        coVerify(exactly = 0) { repository.executeInConversationTerminal(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.executeCommand("ws", "Write-Output '中文'", "", 30_000, null, null, any()) }
    }
}
