package me.ayuilos.miffan.ui.components.message

import me.ayuilos.miffan.testutils.workspaceTestResources
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolTargetUiTest {
    private val resources = workspaceTestResources()

    @Test
    fun remoteApprovalAndHistoryUseCapturedHostAccountAndDirectory() {
        val target = WorkspaceToolTargetSnapshot(
            assistantId = "assistant-1",
            workspacePermissionRevision = "permission-1",
            workspaceId = "workspace-1",
            scopeId = null,
            kind = "REMOTE",
            remoteHostId = "host-1",
            remoteRoot = "/srv/original",
            hostConnectionRevision = "connection-1",
            workspaceName = "Original workspace",
            remoteHostName = "Original server",
            remoteHostLabel = "operator@old.example:2222",
        )

        assertEquals(
            listOf(
                "原始目标：远程服务器 · Original workspace",
                "主机：Original server · operator@old.example:2222",
                "目录：/srv/original",
            ),
            workspaceToolTargetLines(resources, target),
        )
    }

    @Test
    fun oldCallWithoutCapturedTargetIsMarkedUnexecutable() {
        assertTrue(workspaceToolTargetLines(resources, null).single().contains("无法执行"))
    }
}
