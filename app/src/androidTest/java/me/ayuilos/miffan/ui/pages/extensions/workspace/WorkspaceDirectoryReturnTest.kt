package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.Navigator
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf

@RunWith(AndroidJUnit4::class)
class WorkspaceDirectoryReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localDirectorySurvivesPreviewReturn() = verifyReturn(remote = false)
    @Test fun remoteDirectorySurvivesPreviewReturn() = verifyReturn(remote = true)

    private fun verifyReturn(remote: Boolean) {
        val koin = GlobalContext.get()
        val repository = koin.get<WorkspaceRepository>()
        val dao = koin.get<WorkspaceDAO>()
        val id = UUID.randomUUID().toString()
        val workspace = runBlocking {
            if (remote) WorkspaceEntity(
                id = id, name = "Return test $id", root = "remote:$id",
                kind = WorkspaceEntity.KIND_REMOTE, remotePath = "/srv/test",
                createdAt = 1, updatedAt = 1,
            ).also { dao.upsert(it) }
            else repository.create("Return test $id")
        }
        val target = "one/two/three"
        if (!remote) File(koin.get<WorkspaceManager>().filesDir(workspace.root), target).mkdirs()
        // Remote metadata deliberately has no host: navigation must retain its location even
        // offline. This test does not need SSH or access to a user's machine.
        val detail = Screen.WorkspaceDetail(workspace.id, area = "FILES", path = "", openFiles = true)
        val stack = mutableStateListOf<NavKey>(detail)
        val navigator = Navigator(stack)
        lateinit var displayedVm: WorkspaceDetailVM
        try {
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalNavController provides navigator) {
                        NavDisplay(
                            backStack = stack,
                            onBack = { navigator.popBackStack() },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            entryProvider = entryProvider {
                                entry<Screen.WorkspaceDetail> { route ->
                                    displayedVm = koinViewModel(parameters = {
                                        parametersOf(WorkspaceDetailArgs(route.id))
                                    })
                                    WorkspaceDetailPage(route.id, route.area, route.path, route.openFiles)
                                }
                                entry<Screen.WorkspaceFilePreview> { Text("Preview destination") }
                            },
                        )
                    }
                }
            }
            compose.waitForIdle()
            lateinit var originalVm: WorkspaceDetailVM
            compose.runOnIdle {
                originalVm = displayedVm
                displayedVm.navigateTo(WorkspaceStorageArea.FILES, target)
            }
            compose.waitForIdle()
            repeat(2) {
                compose.runOnIdle {
                    navigator.navigate(Screen.WorkspaceFilePreview(workspace.id, "/workspace/$target/file.txt"))
                }
                compose.waitForIdle()
                compose.runOnIdle { navigator.popBackStack() }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertSame(originalVm, displayedVm)
                    assertEquals(WorkspaceStorageArea.FILES, displayedVm.state.value.area)
                    assertEquals(target, displayedVm.state.value.path)
                }
            }
            compose.runOnIdle { displayedVm.goUp() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("one/two", displayedVm.state.value.path) }
        } finally {
            runBlocking { repository.delete(workspace.id) }
        }
    }
}
