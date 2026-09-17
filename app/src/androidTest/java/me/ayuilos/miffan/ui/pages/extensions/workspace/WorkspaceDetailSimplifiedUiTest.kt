package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceDetailSimplifiedUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun untrustedRemoteHasDirectCheckActionWhileConnectionDetailsStayCollapsed() {
        val workspace = WorkspaceEntity(
            id = "remote", name = "Project with a very long descriptive name",
            root = "remote:remote", shellStatus = WorkspaceShellStatus.DISABLED.name,
            createdAt = 1, updatedAt = 1,
            kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/project/reports",
        )
        val host = RemoteHostEntity(
            id = "host", name = "Build server with a long descriptive name",
            host = "node.tailnet", port = 22, username = "builder", authType = "PASSWORD",
            trustedHostKeySha256 = null, createdAt = 1, updatedAt = 1,
        )
        val checks = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(560.dp)) {
                    WorkspaceBasicPage(
                        workspace = workspace, remoteHost = host,
                        remoteHostState = null, remoteWorkspaceState = null,
                        scopeId = null, scopeName = null, installProgress = null,
                        onInstallRootfs = {}, onCheckHost = { checks.value++ },
                        onToolApprovalChange = { _, _ -> },
                    )
                }
            }
        }

        compose.onNodeWithText("确认主机指纹并测试").assertExists().performClick()
        compose.onNodeWithText("主机指纹待确认").assertExists()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, checks.value) }
        compose.onNodeWithText("远程目录").assertDoesNotExist()
        capture("workspace-detail-settings-narrow.png")
        compose.onNodeWithText("查看工作区与连接详情").performClick()
        compose.onNodeWithText("远程目录").assertExists()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
