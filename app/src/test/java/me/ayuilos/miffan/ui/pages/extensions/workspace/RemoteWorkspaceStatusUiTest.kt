package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.testutils.workspaceTestResources
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.RemoteCheckRecord
import me.ayuilos.miffan.data.repository.RemoteConnectionActivity
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteOperationOutcome
import me.ayuilos.miffan.data.repository.RemoteOperationRecord
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkspaceStatusUiTest {
    private val resources = workspaceTestResources()

    private val workspace = WorkspaceEntity(
        id = "workspace-1", name = "Remote project", root = "remote:workspace-1",
        shellStatus = WorkspaceShellStatus.READY.name,
        createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host-1", remotePath = "/srv/project",
    )

    @Test
    fun readyWithoutActiveSessionIsOnlyConfigured() {
        val status = remoteWorkspaceStatusLabel(resources, workspace, null, null)
        assertTrue(status.contains("按需连接"))
        assertFalse(status.contains("已连接"))
    }

    @Test
    fun activeWorkspaceShowsConnectionActivityButIdleShowsPastCheck() {
        val active = RemoteWorkspaceRuntimeState(activity = RemoteConnectionActivity.CONNECTING)
        assertTrue(remoteWorkspaceStatusLabel(resources, workspace, null, active).contains("正在连接"))

        val idle = RemoteWorkspaceRuntimeState(lastDirectoryCheck = RemoteCheckRecord(1_000, true))
        val status = remoteWorkspaceStatusLabel(resources, workspace, null, idle)
        assertTrue(status.contains("上次检查通过"))
        assertFalse(status.contains("已连接"))
    }

    @Test
    fun inaccessibleDirectoryAndCommandFailureDoNotBecomeHostOffline() {
        val directoryFailure = RemoteWorkspaceRuntimeState(
            lastDirectoryCheck = RemoteCheckRecord(1_000, false, "permission denied"),
        )
        assertTrue(remoteWorkspaceStatusLabel(resources, workspace, RemoteHostRuntimeState(), directoryFailure).contains("目录上次检查失败"))

        val commandFailure = RemoteWorkspaceRuntimeState(
            lastDirectoryCheck = RemoteCheckRecord(1_000, true),
            lastOperation = RemoteOperationRecord(2_000, RemoteOperationOutcome.COMMAND_FAILED, exitCode = 1),
        )
        assertTrue(remoteWorkspaceStatusLabel(resources, workspace, null, commandFailure).contains("目录上次检查通过"))
        assertTrue(remoteWorkspaceLastOperationLabel(resources, commandFailure).orEmpty().contains("命令失败"))
    }

    @Test
    fun newerHostFailureOverridesOldDirectorySuccessButNotSameCheckDirectoryFailure() {
        val earlierDirectorySuccess = RemoteWorkspaceRuntimeState(
            lastDirectoryCheck = RemoteCheckRecord(1_000, true),
        )
        val newerHostFailure = RemoteHostRuntimeState(
            lastConnection = RemoteCheckRecord(2_000, false, "network unavailable"),
        )
        assertTrue(remoteWorkspaceStatusLabel(resources, workspace, newerHostFailure, earlierDirectorySuccess)
            .contains("主机上次连接失败"))

        val directoryFailure = RemoteWorkspaceRuntimeState(
            lastDirectoryCheck = RemoteCheckRecord(3_000, false, "permission denied"),
        )
        val sameCheckHostSuccess = RemoteHostRuntimeState(
            lastConnection = RemoteCheckRecord(3_000, true),
        )
        assertTrue(remoteWorkspaceStatusLabel(resources, workspace, sameCheckHostSuccess, directoryFailure)
            .contains("目录上次检查失败"))
    }
    @Test fun liveConnectionDoesNotHideUnknownCommandOutcome() {
        val host = me.ayuilos.miffan.data.db.entity.RemoteHostEntity(
            id = "host", name = "Server", host = "example.invalid", port = 22,
            username = "test", authType = "PASSWORD", trustedHostKeySha256 = "test",
            createdAt = 1, updatedAt = 1,
        )
        val runtime = RemoteWorkspaceRuntimeState(lastOperation = RemoteOperationRecord(
            2_000, RemoteOperationOutcome.OUTCOME_UNKNOWN,
        ))
        org.junit.Assert.assertEquals(
            resources.getString(me.ayuilos.miffan.R.string.workspace_last_outcome_unknown),
            workspaceCardRemoteStatus(resources, host, workspace, null, runtime,
                me.ayuilos.miffan.data.repository.RemoteConnectionStatus.CONNECTED),
        )
    }

}
