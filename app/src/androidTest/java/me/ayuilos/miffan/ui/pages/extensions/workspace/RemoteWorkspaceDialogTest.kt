package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.R
import me.ayuilos.miffan.testutils.workspaceUiText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteWorkspaceDialogTest {
    @get:Rule val compose = createComposeRule()

    private val host = RemoteHostEntity(
        id = "host-id", name = "开发机", host = "100.64.0.2", port = 22,
        username = "dev", authType = "PASSWORD", trustedHostKeySha256 = null,
        createdAt = 0L, updatedAt = 0L,
    )
    private val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun addingAndVerifyingHostPreservesWorkspaceDraft() {
        var addCalls = 0
        var verifiedHostId: String? = null
        var created: Triple<String, String, String>? = null
        var dismissed = false
        compose.setContent {
            var hosts by remember { mutableStateOf(emptyList<RemoteHostEntity>()) }
            var draft by remember { mutableStateOf(RemoteWorkspaceDraft()) }
            MaterialTheme {
                RemoteWorkspaceDialog(
                    hosts = hosts,
                    existingNames = emptySet(),
                    draft = draft,
                    onDraftChange = { draft = it },
                    onDismiss = { dismissed = true },
                    onAddHost = {
                        addCalls++
                        hosts = listOf(host)
                        draft = draft.copy(selectedHostId = host.id)
                    },
                    onVerifyHost = { selected ->
                        verifiedHostId = selected.id
                        hosts = listOf(selected.copy(trustedHostKeySha256 = fingerprint))
                    },
                    onCreate = { name, hostId, directory, callback ->
                        created = Triple(name, hostId, directory)
                        callback(Result.success(WorkspaceEntity(
                            id = "workspace-id", name = name, root = directory,
                            createdAt = 0L, updatedAt = 0L,
                        )))
                    },
                )
            }
        }

        compose.onNodeWithTag("remote_workspace_name").performTextInput("项目 A")
        compose.onNodeWithTag("remote_workspace_directory").performScrollTo().performTextInput("/home/dev/project")
        compose.onNodeWithText(workspaceUiText(R.string.workspace_add_remote_host)).performClick()
        compose.onNodeWithTag("remote_workspace_name").assertTextContains("项目 A")
        compose.onNodeWithTag("remote_workspace_directory").assertTextContains("/home/dev/project")
        compose.onNodeWithTag("remote_workspace_host_host-id").assertIsSelected()
        compose.onNodeWithText(workspaceUiText(R.string.skill_detail_page_create)).assertIsNotEnabled()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_confirm_fingerprint_test)).performScrollTo().performClick()
        compose.onNodeWithText(workspaceUiText(R.string.skill_detail_page_create)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, addCalls)
            assertEquals(host.id, verifiedHostId)
            assertEquals(Triple("项目 A", host.id, "/home/dev/project"), created)
            assertTrue(dismissed)
        }
    }

    @Test
    fun failedCreateCanRetryWhilePendingCreateCannotBeDismissed() {
        var createCalls = 0
        var callback: ((Result<WorkspaceEntity>) -> Unit)? = null
        var dismissCalls = 0
        compose.setContent {
            var draft by remember {
                mutableStateOf(RemoteWorkspaceDraft("项目 B", host.id, "/home/dev/project"))
            }
            MaterialTheme {
                RemoteWorkspaceDialog(
                    hosts = listOf(host.copy(trustedHostKeySha256 = fingerprint)),
                    existingNames = emptySet(),
                    draft = draft,
                    onDraftChange = { draft = it },
                    onDismiss = { dismissCalls++ },
                    onAddHost = {}, onVerifyHost = {},
                    onCreate = { _, _, _, completion ->
                        createCalls++
                        callback = completion
                    },
                )
            }
        }

        compose.onNodeWithText(workspaceUiText(R.string.skill_detail_page_create)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_creating_workspace)).assertExists()
        compose.onNodeWithText(workspaceUiText(R.string.skill_detail_page_create)).assertIsNotEnabled()
        compose.onNodeWithText(workspaceUiText(R.string.common_cancel)).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, createCalls)
            assertEquals(0, dismissCalls)
            callback?.invoke(Result.failure(IllegalStateException("目录不可访问")))
        }
        compose.onNodeWithText("目录不可访问").assertExists()
        compose.onNodeWithTag("remote_workspace_name").assertTextContains("项目 B")
        compose.onNodeWithText(workspaceUiText(R.string.skill_detail_page_create)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, createCalls) }
    }
}
