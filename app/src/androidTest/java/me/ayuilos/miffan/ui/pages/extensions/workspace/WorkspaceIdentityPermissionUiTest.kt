package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.RemoteCheckRecord
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.ayuilos.miffan.ui.pages.assistant.detail.WorkspaceShellPermissionControls
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceIdentityPermissionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun remoteIdentityAndShellCapabilityAreDistinctFromPerCallApproval() {
        val workspace = WorkspaceEntity(
            id = "remote-workspace", name = "Project Alpha", root = "remote:remote-workspace",
            shellStatus = WorkspaceShellStatus.READY.name,
            createdAt = 1, updatedAt = 1,
            kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host-1", remotePath = "/srv/project-alpha",
        )
        val host = RemoteHostEntity(
            id = "host-1", name = "Build server", host = "server.internal", port = 2222,
            username = "builder", authType = "PASSWORD", trustedHostKeySha256 = "fingerprint",
            createdAt = 1, updatedAt = 1,
        )
        compose.setContent {
            var shellEnabled by remember { mutableStateOf(true) }
            var approvalRequired by remember { mutableStateOf(true) }
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorkspaceCard(
                        workspace = workspace,
                        host = host,
                        hostState = RemoteHostRuntimeState(),
                        workspaceState = RemoteWorkspaceRuntimeState(
                            lastDirectoryCheck = RemoteCheckRecord(1_000, success = true),
                        ),
                        onRename = {}, onDelete = {}, onOpen = {},
                    )
                    WorkspaceShellPermissionControls(
                        shellEnabled = shellEnabled,
                        approvalRequired = approvalRequired,
                        isRemote = true,
                        onShellEnabledChange = { shellEnabled = it; if (it) approvalRequired = true },
                        onApprovalRequiredChange = { approvalRequired = it },
                    )
                }
            }
        }

        compose.onNodeWithTag("workspace-card-remote-workspace").assertExists()
        compose.onNodeWithText("远程 · Build server", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("按需连接 · 上次目录检查通过", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("builder@server.internal:2222", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("工作目录不是安全沙箱", substring = true).assertExists()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "remote-identity-permissions.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()

        compose.onNodeWithTag("workspace-shell-capability").performScrollTo().assertIsOn()
        compose.onNodeWithTag("workspace-shell-each-approval").assertIsEnabled().assertIsOn()
        compose.onNodeWithTag("workspace-shell-capability").performClick().assertIsOff()
        compose.onNodeWithTag("workspace-shell-each-approval").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-shell-capability").performClick().assertIsOn()
        compose.onNodeWithTag("workspace-shell-each-approval").assertIsEnabled().performClick().assertIsOff()
    }
}
