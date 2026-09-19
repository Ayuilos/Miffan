package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.testutils.workspaceUiText
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteTerminalNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigatingBackIsSeparateFromExplicitTerminalClose() {
        var backs = 0
        val workspace = WorkspaceEntity(id = "navigation-test", name = "Terminal", root = "remote:test",
            kind = WorkspaceEntity.KIND_REMOTE, remotePath = "/project", createdAt = 1, updatedAt = 1)
        val navigator = Navigator(mutableStateListOf<NavKey>(Screen.WorkspaceDetail(workspace.id)))
        compose.setContent {
            CompositionLocalProvider(LocalNavController provides navigator) {
                // No host is supplied: this test never connects to any server.
                RemoteWorkspaceTerminalPage(workspace, host = null, onBack = { backs++ })
            }
        }
        compose.onNodeWithContentDescription(workspaceUiText(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithText(workspaceUiText(R.string.workspace_close_terminal_title)).assertDoesNotExist()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_connection_disconnect)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_close_terminal_title)).assertIsDisplayed()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_keep_using)).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithText(workspaceUiText(R.string.workspace_connection_disconnect)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_disconnect_back)).performClick()
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
