package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.R
import me.ayuilos.miffan.testutils.workspaceUiText
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.ui.theme.LocalDarkMode
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceHomeUiTest {
    @get:Rule val compose = createComposeRule()

    private val host = RemoteHostEntity(
        id = "host-1", name = "Build server", host = "build.internal", port = 2222,
        username = "builder", authType = "PASSWORD", trustedHostKeySha256 = "fingerprint",
        createdAt = 1, updatedAt = 1,
    )
    private val local = WorkspaceEntity(
        id = "local-1", name = "Local notes", root = "/data/local-notes",
        shellStatus = WorkspaceShellStatus.DISABLED.name, createdAt = 1, updatedAt = 1,
    )
    private val remote = WorkspaceEntity(
        id = "remote-1", name = "Project Alpha", root = "remote:remote-1",
        shellStatus = WorkspaceShellStatus.READY.name, createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = host.id, remotePath = "/srv/project-alpha",
    )
    private val key = SshKeyEntity(
        id = "key-1", name = "Phone key", algorithm = "ssh-ed25519",
        publicKey = "ssh-ed25519 Example", fingerprint = "SHA256:Example",
        createdAt = 1, updatedAt = 1,
    )

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun render(
        dark: Boolean,
        narrow: Boolean,
        workspaces: List<WorkspaceEntity>,
        hosts: List<RemoteHostEntity>,
        keys: List<SshKeyEntity>,
        actions: WorkspaceHomeActions = WorkspaceHomeActions(),
    ) {
        compose.setContent {
            var homeTab by remember { mutableStateOf(WorkspaceHomeTab.WORKSPACES) }
            var connectionTab by remember { mutableStateOf(WorkspaceConnectionTab.HOSTS) }
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf(WorkspaceListFilter.ALL) }
            CompositionLocalProvider(LocalDarkMode provides dark) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            topBar = { WorkspaceHomeToolbar(homeTab, connectionTab, {}, actions) },
                            modifier = if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize(),
                        ) { insets ->
                            WorkspaceHomeContent(
                                workspaces = workspaces,
                                hosts = hosts,
                                sshKeys = keys,
                                keyMaterialStatus = keys.associate { it.id to true },
                                remoteHostStates = emptyMap(),
                                remoteWorkspaceStates = emptyMap(),
                                homeTab = homeTab,
                                connectionTab = connectionTab,
                                query = query,
                                filter = filter,
                                onHomeTabChange = { homeTab = it },
                                onConnectionTabChange = { connectionTab = it },
                                onQueryChange = { query = it },
                                onFilterChange = { filter = it },
                                actions = actions,
                                modifier = Modifier.padding(insets),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun narrowLightHomeSearchFilterAndConnectionTabs() {
        render(false, true, listOf(local, remote), listOf(host), listOf(key))
        compose.onNodeWithTag("workspace-tab-workspaces").assertIsSelected()
        compose.onNodeWithTag("workspace-card-local-1").assertExists()
        compose.onNodeWithTag("workspace-card-remote-1").assertExists()
        screenshot("workspace-home-light-narrow.png")

        compose.onNodeWithTag("workspace-search").performTextInput("build.internal")
        compose.onNodeWithTag("workspace-card-remote-1").assertExists()
        compose.onNodeWithTag("workspace-card-local-1").assertDoesNotExist()
        compose.onNodeWithTag("workspace-filter-local").performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_no_matches)).assertExists()
        compose.onNodeWithTag("workspace-empty-action").performClick()
        compose.onNodeWithTag("workspace-card-local-1").assertExists()
        compose.onNodeWithTag("workspace-filter-remote").performClick()
        compose.onNodeWithTag("workspace-card-remote-1").assertExists()
        compose.onNodeWithTag("workspace-card-local-1").assertDoesNotExist()

        compose.onNodeWithTag("workspace-tab-hosts").performClick().assertIsSelected()
        compose.onNodeWithTag("workspace-tab-hosts").assertIsSelected()
        compose.onNodeWithText("Build server", useUnmergedTree = true).assertExists()
        screenshot("workspace-home-connections-light.png")
        compose.onNodeWithTag("workspace-tab-keys").performClick().assertIsSelected()
        compose.onNodeWithText("Phone key", useUnmergedTree = true).assertExists()
    }

    @Test
    fun darkEmptyStatesOfferDirectActions() {
        var createCount = 0
        var hostCount = 0
        var keyCount = 0
        render(
            dark = true, narrow = false,
            workspaces = emptyList(), hosts = emptyList(), keys = emptyList(),
            actions = WorkspaceHomeActions(
                createWorkspace = { createCount++ },
                addHost = { hostCount++ },
                keyActions = { keyCount++ },
            ),
        )
        compose.onNodeWithText(workspaceUiText(R.string.workspace_empty_workspaces)).assertExists()
        screenshot("workspace-home-empty-dark.png")
        compose.onNodeWithTag("workspace-empty-action").performClick()
        compose.runOnIdle { assertEquals(1, createCount) }

        compose.onNodeWithTag("workspace-tab-hosts").performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_empty_hosts)).assertExists()
        screenshot("workspace-home-hosts-empty-dark.png")
        compose.onNodeWithTag("workspace-empty-action").performClick()
        compose.runOnIdle { assertEquals(1, hostCount) }

        compose.onNodeWithTag("workspace-tab-keys").performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_empty_keys)).assertExists()
        compose.onNodeWithTag("workspace-empty-action").performClick()
        compose.runOnIdle { assertEquals(1, keyCount) }
    }
    @Test
    fun creationSheetShowsClearLocalAndRemoteChoices() {
        var localCount = 0
        var remoteCount = 0
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                WorkspaceCreateSheet({}, { localCount++ }, { remoteCount++ })
            }
        }
        compose.onNodeWithText(workspaceUiText(R.string.workspace_local_workspace)).assertExists()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_remote_workspace)).assertExists()
        screenshot("workspace-create-sheet.png")
        compose.onNodeWithText(workspaceUiText(R.string.workspace_local_workspace)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_remote_workspace)).performClick()
        compose.runOnIdle {
            assertEquals(1, localCount)
            assertEquals(1, remoteCount)
        }
    }

}
