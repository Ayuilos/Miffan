package me.ayuilos.miffan.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantRemoverSpaceTest {
    private val box = WorkspaceEntity(
        id = Uuid.random().toString(), name = "Partner · Server", root = "space:1", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_SPACE, remoteHostId = "host", spaceName = "miffan-1",
    )
    private val partner = Assistant(workspaceId = Uuid.parse(box.id))

    private fun remover(settings: MutableStateFlow<Settings>, deleted: MutableList<String>, fail: Boolean = false): AssistantRemover {
        val store = mockk<SettingsStore>()
        coEvery { store.update(any<(Settings) -> Settings>()) } answers {
            settings.value = firstArg<(Settings) -> Settings>().invoke(settings.value)
        }
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        coEvery { workspaces.getById(box.id) } returns box
        return AssistantRemover(store, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), workspaces,
            deleteSpace = { id -> if (fail) error("unreachable") else deleted += id })
    }

    @Test
    fun `a partner's mini box goes with it only when asked`() = runTest {
        val kept = mutableListOf<String>()
        val settings = MutableStateFlow(Settings(assistants = listOf(partner)))
        remover(settings, kept).remove(partner)
        assertTrue(kept.isEmpty())

        val deleted = mutableListOf<String>()
        settings.value = Settings(assistants = listOf(partner))
        remover(settings, deleted).remove(partner, withSpace = true)
        assertEquals(listOf(box.id), deleted)
        assertTrue(settings.value.assistants.isEmpty())
    }

    @Test
    fun `a mini box out of reach does not stop the partner's deletion`() = runTest {
        val settings = MutableStateFlow(Settings(assistants = listOf(partner)))
        remover(settings, mutableListOf(), fail = true).remove(partner, withSpace = true)
        assertTrue(settings.value.assistants.isEmpty())
    }
}
