package me.ayuilos.miffan.data.ai.computer

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.ComputerUseMode
import me.ayuilos.miffan.data.repository.RemoteHostCredentialStore
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerComputersTest {
    private fun workspace(number: Int, createdAt: Long = number.toLong(), hostId: String = "host") = WorkspaceEntity(
        id = Uuid.fromLongs(0, number.toLong()).toString(), name = "Workspace $number", root = "remote:$number",
        createdAt = createdAt, updatedAt = 100 - createdAt, kind = WorkspaceEntity.KIND_REMOTE,
        remoteHostId = hostId, remotePath = "/home/user",
    )

    private fun host(id: String = "host", name: String = "Server", port: Int = 22) = RemoteHostEntity(
        id = id, name = name, host = "$id.internal", port = port, username = "user", authType = "PASSWORD",
        trustedHostKeySha256 = "fingerprint", createdAt = 1, updatedAt = 1,
        screenEnabled = true, screenPlatform = "linux",
    )

    private fun assistant(workspace: WorkspaceEntity? = null, mode: ComputerUseMode = ComputerUseMode.AUTO) =
        Assistant(workspaceId = workspace?.id?.let(Uuid::parse), computerUse = mode)

    private class Fixture {
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val workspaceList = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        val hosts = MutableStateFlow<List<RemoteHostEntity>>(emptyList())
        val store = mockk<SettingsStore>()
        val repository = mockk<WorkspaceRepository>()
        val computers = PartnerComputers(store, repository)

        init {
            every { store.settingsFlow } returns settings
            coEvery { store.update(any<(Settings) -> Settings>()) } answers {
                settings.value = firstArg<(Settings) -> Settings>().invoke(settings.value)
            }
            every { repository.listFlow() } returns workspaceList
            every { repository.listHostsFlow() } returns hosts
            coEvery { repository.getHostById(any()) } answers { hosts.value.find { it.id == firstArg<String>() } }
        }
    }

    @Test
    fun `most partners wins regardless of workspace update order`() = runTest {
        val f = Fixture()
        val oldest = workspace(1)
        val popular = workspace(2)
        f.hosts.value = listOf(host())
        f.workspaceList.value = listOf(oldest, popular)
        f.settings.value = f.settings.value.copy(assistants = listOf(assistant(oldest), assistant(popular), assistant(popular)))

        assertEquals(popular.id, f.computers.observeAll().first().single().workspaceId)
        assertEquals(popular.id, f.computers.observeComputer("host").first()?.workspaceId)
        assertEquals(popular.id, f.computers.ensureComputerWorkspace("host"))
        // A partner's own binding remains its original workspace, even if it is not the computer workspace.
        assertEquals(oldest.id, f.computers.observe(f.settings.value.assistants.first().id).first()?.workspaceId)
        coVerify(exactly = 0) { f.repository.remoteHome(any()) }
    }

    @Test
    fun `ties and no partners use creation time then id instead of list order`() = runTest {
        val f = Fixture()
        val first = workspace(1, createdAt = 10)
        val tied = workspace(2, createdAt = 10)
        val newer = workspace(3, createdAt = 20)
        val local = workspace(4, createdAt = 0).copy(kind = WorkspaceEntity.KIND_LOCAL)
        f.hosts.value = listOf(host())
        f.workspaceList.value = listOf(local, newer, tied, first, workspace(5, createdAt = 0, hostId = "other"))
        assertEquals(first.id, f.computers.ensureComputerWorkspace("host"))
        assertEquals(first.id, f.computers.observeAll().first().single().workspaceId)
        f.settings.value = f.settings.value.copy(assistants = listOf(assistant(newer), assistant(first), assistant(tied)))
        assertEquals(first.id, f.computers.ensureComputerWorkspace("host"))
        assertEquals(first.id, f.computers.observeComputer("host").first()?.workspaceId)
    }

    @Test
    fun `all hosts map addresses platforms counts and partners across workspaces in settings order`() = runTest {
        val f = Fixture()
        val one = workspace(1)
        val two = workspace(2)
        val three = workspace(3)
        val p2 = assistant(two)
        val p1 = assistant(one)
        val elsewhere = assistant(workspace(4, hostId = "alpha"))
        f.hosts.value = listOf(host(name = "zeta", port = 2222), host("alpha", "Alpha").copy(screenEnabled = false, screenPlatform = "macos"))
        f.workspaceList.value = listOf(three, two, one)
        f.settings.value = f.settings.value.copy(assistants = listOf(p2, assistant(), elsewhere, p1))

        val computers = f.computers.observeAll().first()
        assertEquals(listOf("Alpha", "zeta"), computers.map { it.name })
        assertEquals(KnownComputer("alpha", "Alpha", "user@alpha.internal", RemoteScreenPlatform.MACOS, false, null, 0, emptyList()), computers[0])
        assertEquals(KnownComputer("host", "zeta", "user@host.internal:2222", RemoteScreenPlatform.LINUX, true, one.id, 2, listOf(p2.id, p1.id)), computers[1])
        assertNull(f.computers.observeComputer("missing").first())
    }

    @Test
    fun `observers react to binding and host changes but suppress equivalent results`() = runTest {
        val f = Fixture()
        val one = workspace(1)
        val two = workspace(2)
        f.hosts.value = listOf(host())
        f.workspaceList.value = listOf(two, one)
        val all = mutableListOf<List<KnownComputer>>()
        val single = mutableListOf<KnownComputer?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.computers.observeAll().collect { all += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.computers.observeComputer("host").collect { single += it } }
        runCurrent()
        f.workspaceList.value = listOf(one, two.copy(updatedAt = 999))
        runCurrent()
        assertEquals(1, all.size)
        assertEquals(1, single.size)
        f.settings.value = f.settings.value.copy(assistants = listOf(assistant(two)))
        runCurrent()
        assertEquals(two.id, single.last()?.workspaceId)
        f.hosts.value = emptyList()
        runCurrent()
        assertEquals(emptyList<KnownComputer>(), all.last())
        assertNull(single.last())
    }

    @Test
    fun `missing workspace is created at remote home with a unique host based name`() = runTest {
        val f = Fixture()
        f.hosts.value = listOf(host())
        val created = workspace(1)
        coEvery { f.repository.remoteHome("host") } returns "/Users/user"
        coEvery { f.repository.isNameTaken(any(), null) } answers { firstArg<String>() in setOf("Server", "Server 2") }
        coEvery { f.repository.createRemoteWorkspace("Server 3", "host", "/Users/user") } returns created
        assertEquals(created.id, f.computers.ensureComputerWorkspace("host"))
        coVerify(exactly = 1) { f.repository.createRemoteWorkspace("Server 3", "host", "/Users/user") }
    }

    @Test
    fun `bind preserves every existing host binding and gives new partners ASK in one update`() = runTest {
        val f = Fixture()
        val one = workspace(1)
        val two = workspace(2)
        val other = workspace(3, hostId = "other")
        val existing = assistant(one, ComputerUseMode.AUTO)
        val existingOtherWorkspace = assistant(two, ComputerUseMode.OFF)
        val new = assistant(other, ComputerUseMode.OFF)
        val unbound = assistant()
        val untouched = assistant(other)
        f.workspaceList.value = listOf(other, two, one)
        f.settings.value = f.settings.value.copy(assistants = listOf(existing, existingOtherWorkspace, new, unbound, untouched))
        f.computers.bind("host", listOf(existing.id, existingOtherWorkspace.id, new.id, unbound.id, Uuid.random()))

        val after = f.settings.value.assistants
        assertEquals(existing, after[0])
        assertEquals(existingOtherWorkspace, after[1])
        for (index in listOf(2, 3)) {
            assertEquals(Uuid.parse(one.id), after[index].workspaceId)
            assertEquals(after[index].id, after[index].workspaceScopeId)
            assertEquals(ComputerUseMode.ASK, after[index].computerUse)
            assertTrue(after[index].workspaceShellApprovalRequired)
        }
        assertNotEquals(new.workspacePermissionRevision, after[2].workspacePermissionRevision)
        assertEquals(untouched, after[4])
        coVerify(exactly = 1) { f.store.update(any<(Settings) -> Settings>()) }
    }

    @Test
    fun `empty bind does not read or create anything`() = runTest {
        val f = Fixture()
        f.computers.bind("missing", emptySet())
        verify(exactly = 0) { f.repository.listFlow() }
        coVerify(exactly = 0) { f.repository.getHostById(any()) }
        coVerify(exactly = 0) { f.store.update(any<(Settings) -> Settings>()) }
    }

    @Test
    fun `delete refuses other workspaces without changing settings workspace or host`() = runTest {
        val f = Fixture()
        f.hosts.value = listOf(host())
        f.workspaceList.value = listOf(workspace(1), workspace(2), workspace(3))
        f.settings.value = f.settings.value.copy(assistants = listOf(assistant(f.workspaceList.value[1])))
        val before = f.settings.value
        val error = runCatching { f.computers.delete("host") }.exceptionOrNull()
        assertTrue(error is ComputerHasWorkspacesException)
        assertEquals(2, (error as ComputerHasWorkspacesException).count)
        assertEquals(before, f.settings.value)
        coVerify(exactly = 0) { f.repository.delete(any()) }
        coVerify(exactly = 0) { f.repository.deleteHost(any()) }
        coVerify(exactly = 0) { f.store.update(any<(Settings) -> Settings>()) }
    }

    @Test
    fun `delete removes workspace then host credentials and unbinds partners through repository`() = runTest {
        val f = Fixture()
        val only = workspace(1)
        val partner = assistant(only)
        val untouched = assistant(workspace(2, hostId = "other"))
        f.settings.value = f.settings.value.copy(assistants = listOf(partner, untouched))
        f.workspaceList.value = listOf(only)
        f.hosts.value = listOf(host())
        val dao = mockk<WorkspaceDAO>()
        val hostDao = mockk<RemoteHostDAO>()
        val credentials = mockk<RemoteHostCredentialStore>(relaxed = true)
        every { dao.listFlow() } returns f.workspaceList
        coEvery { dao.getById(only.id) } answers { f.workspaceList.value.find { it.id == only.id } }
        coEvery { dao.deleteById(only.id) } answers { f.workspaceList.value = emptyList(); 1 }
        coEvery { dao.countByRemoteHostId("host") } answers { f.workspaceList.value.size }
        coEvery { hostDao.getById("host") } answers { f.hosts.value.firstOrNull() }
        coEvery { hostDao.deleteById("host") } answers { f.hosts.value = emptyList(); 1 }
        val repository = WorkspaceRepository(dao, mockk(), mockk(), mockk(), f.store, hostDao,
            credentials, mockk(), mockk(), mockk(), mockk(relaxed = true))

        PartnerComputers(f.store, repository).delete("host")

        assertTrue(f.workspaceList.value.isEmpty())
        assertTrue(f.hosts.value.isEmpty())
        assertNull(f.settings.value.assistants[0].workspaceId)
        assertNull(f.settings.value.assistants[0].workspaceScopeId)
        assertEquals(untouched, f.settings.value.assistants[1])
        coVerifyOrder { dao.deleteById(only.id); f.store.update(any<(Settings) -> Settings>()); hostDao.deleteById("host") }
        verify(exactly = 1) { credentials.delete("host") }
    }

    @Test
    fun `delete host with no workspace does not create one`() = runTest {
        val f = Fixture()
        coEvery { f.repository.deleteHost("host") } returns true
        f.computers.delete("host")
        coVerify(exactly = 1) { f.repository.deleteHost("host") }
        coVerify(exactly = 0) { f.repository.delete(any()) }
        coVerify(exactly = 0) { f.repository.remoteHome(any()) }
    }
}
