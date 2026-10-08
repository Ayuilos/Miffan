package me.ayuilos.miffan.ui.im.computer

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ayuilos.miffan.data.ai.computer.PartnerComputers
import me.ayuilos.miffan.data.ai.computer.RemoteComputerRegistry
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteHostKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class ComputerSetupVMTest {
    private val viewModels = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private class Fixture {
        var host = RemoteHostEntity(
            id = "host", name = "Server", host = "server.internal", port = 22, username = "user",
            authType = "PASSWORD", trustedHostKeySha256 = "fingerprint", createdAt = 1, updatedAt = 1,
        )
        val key = RemoteHostKey("ssh-ed25519", "new fingerprint")
        val workspaceId = Uuid.random().toString()
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val workspaceList = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        val store = mockk<SettingsStore>()
        val workspaces = mockk<WorkspaceRepository>()
        val screens = mockk<RemoteScreenRepository>()
        val registry = mockk<RemoteComputerRegistry>()
        val computers = mockk<PartnerComputers>()
        val probe = RemoteMachineProbe(
            helper = 5, os = "linux", arch = "x86_64",
            session = RemoteMachineProbe.Session(present = true, type = "wayland"),
            cua = RemoteMachineProbe.Cua(min = "0.34.0", ok = true),
            vnc = RemoteMachineProbe.Vnc(server = "wayvnc"),
        )

        init {
            every { store.settingsFlow } returns settings
            every { workspaces.listFlow() } returns workspaceList
            every { workspaces.listHostsFlow() } returns MutableStateFlow(listOf(host))
            every { workspaces.listSshKeysFlow() } returns MutableStateFlow(emptyList())
            coEvery { workspaces.getHostById("host") } answers { host }
            coEvery { workspaces.discoverHostKey("host") } returns key
            coEvery { workspaces.testHost("host") } returns true
            coEvery { computers.ensureComputerWorkspace("host") } returns workspaceId
            coEvery { screens.probeHost("host", any()) } returns probe
            coEvery { workspaces.updateHost(any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { computers.bind("host", any()) } returns Unit
        }
    }

    private fun vm(f: Fixture, args: ComputerSetupArgs = ComputerSetupArgs()): ComputerSetupVM =
        ComputerSetupVM(args, f.workspaces, f.screens, f.registry, f.store, f.computers).also {
            viewModels.put(Uuid.random().toString(), it)
        }

    private fun workspace(id: String = Uuid.random().toString(), hostId: String = "host") = WorkspaceEntity(
        id = id, name = "Remote", root = "remote:$id", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = hostId, remotePath = "/home/user",
    )

    @Test
    fun `edit starts at address without connecting and address remains first step`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        runCurrent()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
        assertEquals("host", vm.state.value.hostId)
        assertTrue(vm.state.value.editing)
        assertFalse(vm.state.value.busy)
        vm.back()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
        coVerify(exactly = 0) { f.workspaces.getHostById(any()) }
        coVerify(exactly = 0) { f.workspaces.testHost(any()) }
        coVerify(exactly = 0) { f.workspaces.discoverHostKey(any()) }
    }

    @Test
    fun `known host without edit connects and uses the shared computer workspace`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        assertEquals(ComputerSetupStep.PREPARE, vm.state.value.step)
        assertEquals(f.workspaceId, vm.state.value.workspaceId)
        assertEquals(f.probe, vm.state.value.probe)
        assertFalse(vm.state.value.editing)
        coVerify(exactly = 1) { f.computers.ensureComputerWorkspace("host") }
        vm.back()
        assertEquals(ComputerSetupStep.CHOOSE, vm.state.value.step)
    }

    @Test
    fun `untrusted known host without edit reads fingerprint`() = runTest {
        val f = Fixture()
        f.host = f.host.copy(trustedHostKeySha256 = null)
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        assertEquals(ComputerSetupStep.VERIFY, vm.state.value.step)
        assertEquals(f.key, vm.state.value.hostKey)
        coVerify(exactly = 0) { f.workspaces.testHost(any()) }
    }

    @Test
    fun `no host starts at choose and adding a new computer returns there on back`() = runTest {
        val vm = vm(Fixture(), ComputerSetupArgs(edit = true))
        assertEquals(ComputerSetupStep.CHOOSE, vm.state.value.step)
        assertFalse(vm.state.value.editing)
        vm.addNewComputer()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
        vm.back()
        assertEquals(ComputerSetupStep.CHOOSE, vm.state.value.step)
    }

    @Test
    fun `edit with null auth keeps saved sign in and trusted host goes directly to prepare`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        vm.submitAddress("Renamed", "server.internal", 22, "user", null)
        runCurrent()
        coVerify(exactly = 1) { f.workspaces.updateHost("host", "Renamed", "server.internal", 22, "user", null, null) }
        coVerify(exactly = 0) { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.workspaces.discoverHostKey(any()) }
        assertEquals(ComputerSetupStep.PREPARE, vm.state.value.step)
        assertTrue(vm.state.value.editing)
        vm.back()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
    }

    @Test
    fun `edit rereads updated host and requires fingerprint after endpoint change`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.updateHost(any(), any(), any(), any(), any(), any(), any()) } answers {
            f.host = f.host.copy(host = "new.internal", trustedHostKeySha256 = null)
            true
        }
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        vm.submitAddress("Server", "new.internal", 2222, "user", ComputerSetupAuth.Password("new password"))
        runCurrent()
        coVerify { f.workspaces.updateHost("host", "Server", "new.internal", 2222, "user", RemoteAuthentication.Password("new password"), null) }
        assertEquals(ComputerSetupStep.VERIFY, vm.state.value.step)
        assertEquals(f.key, vm.state.value.hostKey)
        coVerify(exactly = 0) { f.workspaces.testHost(any()) }
        vm.back()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
    }

    @Test
    fun `edit with app key updates saved key`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        vm.submitAddress("Server", "server.internal", 22, "user", ComputerSetupAuth.AppKey("key"))
        runCurrent()
        coVerify { f.workspaces.updateHost("host", "Server", "server.internal", 22, "user", null, "key") }
        assertNull(vm.state.value.error)
    }

    @Test
    fun `new computer requires auth and then saves password or app key`() = runTest {
        val f = Fixture()
        f.host = f.host.copy(trustedHostKeySha256 = null)
        val vm = vm(f)
        vm.addNewComputer()
        vm.submitAddress("Server", "server.internal", 22, "user", null)
        runCurrent()
        assertTrue(vm.state.value.error is IllegalStateException)
        coVerify(exactly = 0) { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) }
        coEvery { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) } answers { f.host }
        vm.submitAddress("Server", "server.internal", 22, "user", ComputerSetupAuth.Password("password"))
        runCurrent()
        coVerify { f.workspaces.createHost("Server", "server.internal", 22, "user", RemoteAuthentication.Password("password"), null) }
        assertEquals(ComputerSetupStep.VERIFY, vm.state.value.step)
        assertNull(vm.state.value.error)
        vm.addNewComputer()
        vm.submitAddress("Server", "server.internal", 22, "user", ComputerSetupAuth.AppKey("key"))
        runCurrent()
        coVerify { f.workspaces.createHost("Server", "server.internal", 22, "user", null, "key") }
        assertEquals(ComputerSetupStep.VERIFY, vm.state.value.step)
    }

    @Test
    fun `bound partners follows all remote workspaces settings and current host`() = runTest {
        val f = Fixture()
        val one = workspace()
        val two = workspace()
        val local = workspace().copy(kind = WorkspaceEntity.KIND_LOCAL)
        val other = workspace(hostId = "other")
        val partners = listOf(one, two, local, other).map { Assistant(workspaceId = Uuid.parse(it.id)) }
        f.workspaceList.value = listOf(one, two, local, other)
        f.settings.value = f.settings.value.copy(assistants = partners)
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        runCurrent()
        assertEquals(partners.take(2).map { it.id }.toSet(), vm.boundPartnerIds.value)
        assertNull(vm.replacedBinding.value)
        f.settings.value = f.settings.value.copy(assistants = listOf(partners[1]))
        runCurrent()
        assertEquals(setOf(partners[1].id), vm.boundPartnerIds.value)
        f.workspaceList.value = listOf(one)
        runCurrent()
        assertTrue(vm.boundPartnerIds.value.isEmpty())
        vm.addNewComputer()
        f.workspaceList.value = listOf(one, two)
        runCurrent()
        assertTrue(vm.boundPartnerIds.value.isEmpty())
    }

    @Test
    fun `bind delegates selected partners and allows keeping computer without partners`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        val ids = setOf(Uuid.random(), Uuid.random())
        vm.bind(ids)
        runCurrent()
        coVerify(exactly = 1) { f.computers.bind("host", ids) }
        assertEquals(ComputerSetupStep.DONE, vm.state.value.step)
        vm.bind(emptySet())
        runCurrent()
        coVerify(exactly = 1) { f.computers.bind("host", emptySet()) }
        assertEquals(ComputerSetupStep.DONE, vm.state.value.step)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `failed bind displays error without finishing`() = runTest {
        val f = Fixture()
        val error = IllegalStateException("No workspace")
        coEvery { f.computers.bind("host", any()) } throws error
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        vm.bind(setOf(Uuid.random()))
        runCurrent()
        assertEquals(ComputerSetupStep.ADDRESS, vm.state.value.step)
        assertEquals(error, vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }
}
