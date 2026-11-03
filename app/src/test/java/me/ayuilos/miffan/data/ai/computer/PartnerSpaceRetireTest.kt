package me.ayuilos.miffan.data.ai.computer

import android.content.res.Resources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.data.thread.ThreadService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/** A partner's old mini box: deleted after its new one when the user asked, given to another partner when idle. */
class PartnerSpaceRetireTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun setup(spaces: PartnerSpaces) =
        PartnerSpaceSetup(spaces, mockk<ThreadService>(relaxed = true), AppScope(), mockk<Resources>(relaxed = true))

    @Test
    fun `the old mini box goes only after the new one is made`() = runTest(main) {
        val spaces = mockk<PartnerSpaces>()
        val partner = Uuid.random()
        val other = Uuid.random()
        coEvery { spaces.create("host", any(), any()) } returns CreatedSpace("new", null)
        coEvery { spaces.delete("old") } returns Unit
        val setup = setup(spaces)

        setup.start("host", listOf(partner, other), retire = mapOf(partner to "old"))
        advanceUntilIdle()

        coVerifyOrder {
            spaces.create("host", partner, any())
            spaces.delete("old")
            spaces.create("host", other, any())
        }
        coVerify(exactly = 1) { spaces.delete(any()) }
        assertEquals(SpaceSetupJob.Stage.DONE, setup.jobs.value.getValue("host").stage)
    }

    @Test
    fun `an old mini box out of reach stays without failing the run`() = runTest(main) {
        val spaces = mockk<PartnerSpaces>()
        val partner = Uuid.random()
        coEvery { spaces.create("host", partner, any()) } returns CreatedSpace("new", null)
        coEvery { spaces.delete("old") } throws IllegalStateException("unreachable")
        val setup = setup(spaces)

        setup.start("host", listOf(partner), retire = mapOf(partner to "old"))
        advanceUntilIdle()

        assertEquals(SpaceSetupJob.Stage.DONE, setup.jobs.value.getValue("host").stage)
        setup.retire(listOf("old"))
        advanceUntilIdle()
        coVerify(exactly = 2) { spaces.delete("old") }
    }

    @Test
    fun `an idle mini box goes to its new partner with its name`() = runTest(main) {
        val box = WorkspaceEntity(
            id = Uuid.random().toString(), name = "Old · Server", root = "space:1", createdAt = 1, updatedAt = 1,
            kind = WorkspaceEntity.KIND_SPACE, remoteHostId = "host", spaceName = "miffan-1",
        )
        val partner = Assistant(name = "New", computerUse = ComputerUseMode.ASK)
        val settings = MutableStateFlow(Settings(assistants = listOf(partner)))
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns settings
        coEvery { store.update(any<(Settings) -> Settings>()) } answers {
            settings.value = firstArg<(Settings) -> Settings>().invoke(settings.value)
        }
        val workspaces = mockk<WorkspaceRepository>()
        coEvery { workspaces.getById(box.id) } returns box
        coEvery { workspaces.getHostById("host") } returns RemoteHostEntity(
            id = "host", name = "Server", host = "server.internal", port = 22, username = "user", authType = "PASSWORD",
            trustedHostKeySha256 = "fingerprint", createdAt = 1, updatedAt = 1,
        )
        coEvery { workspaces.isNameTaken(any(), box.id) } returns false
        coEvery { workspaces.rename(box.id, any()) } returns true
        val spaces = PartnerSpaces(store, workspaces, AppScope(), SpaceFileLog(Files.createTempDirectory("space-log").toFile()))

        spaces.assign(box.id, partner.id)

        coVerify { workspaces.rename(box.id, "New · Server") }
        val bound = settings.value.assistants.single()
        assertEquals(Uuid.parse(box.id), bound.workspaceId)
        assertEquals(ComputerUseMode.AUTO, bound.computerUse)
    }
}
