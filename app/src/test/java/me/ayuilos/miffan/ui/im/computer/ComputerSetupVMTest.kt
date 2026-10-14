package me.ayuilos.miffan.ui.im.computer

import androidx.lifecycle.ViewModelStore
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
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
import me.ayuilos.miffan.data.db.entity.RemoteScreenAuth
import me.ayuilos.miffan.data.db.entity.RemoteScreenEndpoint
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.remote.NearbyComputer
import me.ayuilos.miffan.data.remote.NearbyNameSource
import me.ayuilos.miffan.data.remote.NearbyNetwork
import me.ayuilos.miffan.data.remote.NearbySystem
import me.ayuilos.miffan.data.repository.RemoteMachineProbe
import me.ayuilos.miffan.data.repository.RemoteScreenRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.RemoteAuthentication
import me.rerere.workspace.RemoteConnectException
import me.rerere.workspace.RemoteHostKey
import me.rerere.workspace.RemotePasswordSignInFailure
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val appKey = SshKeyEntity("key", "Miffan", "ssh-ed25519", "ssh-ed25519 AAAA miffan", "fp", 1, 1)
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
            every { workspaces.listSshKeysFlow() } returns MutableStateFlow(listOf(appKey))
            coEvery { workspaces.getSshKeyById("key") } returns appKey
            coEvery { workspaces.getHostById("host") } answers { host }
            coEvery { workspaces.discoverHostKey("host") } returns key
            coEvery { workspaces.trustHostKey("host", any()) } answers { host = host.copy(trustedHostKeySha256 = secondArg()); true }
            coEvery { workspaces.testHost("host") } returns true
            coEvery { workspaces.createHost(any(), any(), any(), any(), any(), any()) } answers { host = host.copy(trustedHostKeySha256 = null); host }
            coEvery { workspaces.updateHost(any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { workspaces.executeCommand(any(), any(), any(), any(), any(), any(), any()) } returns WorkspaceCommandResult(0, "", "")
            coEvery { computers.ensureComputerWorkspace("host") } returns workspaceId
            coEvery { computers.bind("host", any()) } returns Unit
            coEvery { screens.probeHost("host", any()) } returns probe
            coEvery { screens.getConfig("host") } returns null
            coEvery { screens.updateConfig(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
            coEvery { screens.streamStatus(any(), any()) } throws IllegalStateException("no sunshine")
        }
    }

    private fun vm(f: Fixture, args: ComputerSetupArgs = ComputerSetupArgs()): ComputerSetupVM =
        ComputerSetupVM(args, f.workspaces, f.screens, f.registry, f.store, f.computers).also {
            viewModels.put(Uuid.random().toString(), it)
        }

    private fun nearby(system: NearbySystem = NearbySystem.LINUX, host: String = "10.0.0.2") =
        NearbyComputer("box", host, 22, system)

    private fun failure(password: RemotePasswordSignInFailure? = null, message: String = "Auth fail") =
        RemoteConnectException("server.internal", JSchException(message), password)

    @Test
    fun `setup starts by finding and leaves on back`() = runTest {
        val vm = vm(Fixture())
        assertEquals(ComputerSetupStep.FIND, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun `edit starts at the connection settings without connecting`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host", edit = true))
        runCurrent()
        assertEquals(ComputerSetupStep.MANUAL, vm.state.value.step)
        assertTrue(vm.state.value.editing)
        assertEquals("server.internal", vm.state.value.target?.host)
        assertFalse(vm.back())
        coVerify(exactly = 0) { f.workspaces.testHost(any()) }
    }

    @Test
    fun `checking a saved computer opens on its checks`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
        assertEquals(f.workspaceId, vm.state.value.workspaceId)
        assertEquals(f.probe, vm.state.value.probe)
        assertTrue(vm.state.value.ready)
        assertFalse(vm.back())
    }

    @Test
    fun `a first connection trusts the computer by itself`() = runTest {
        val f = Fixture()
        f.host = f.host.copy(trustedHostKeySha256 = null)
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        coVerify { f.workspaces.trustHostKey("host", "new fingerprint") }
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
    }

    @Test
    fun `a changed identity stops at the warning`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.testHost("host") } throws RemoteConnectException("server.internal", mockk<JSchUnknownHostKeyException>(relaxed = true))
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        assertEquals(ComputerSetupStep.VERIFY, vm.state.value.step)
        assertEquals(f.key, vm.state.value.hostKey)
        coVerify(exactly = 0) { f.workspaces.trustHostKey(any(), any()) }
    }

    @Test
    fun `a found computer asks for its account, Windows for its script`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.pickNearby(nearby())
        assertEquals(ComputerSetupStep.SIGN_IN, vm.state.value.step)
        assertEquals("10.0.0.2", vm.state.value.target?.host)
        assertTrue(vm.back())
        vm.pickNearby(nearby(NearbySystem.WINDOWS))
        assertEquals(ComputerSetupStep.WINDOWS, vm.state.value.step)
    }

    @Test
    fun `a found computer Miffan already knows is checked again`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        runCurrent()
        vm.pickNearby(nearby(host = "server.internal"))
        runCurrent()
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
        coVerify(exactly = 0) { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) }
        // Back from the checks returns to finding, never to signing in.
        assertTrue(vm.back())
        assertEquals(ComputerSetupStep.FIND, vm.state.value.step)
    }

    @Test
    fun `the same computer found on another network is the saved one`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        runCurrent()
        vm.pickNearby(NearbyComputer("frigate", "100.64.0.5", 22, NearbySystem.UNKNOWN,
            network = NearbyNetwork.TAILSCALE, hostKey = "fingerprint"))
        runCurrent()
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
        coVerify(exactly = 0) { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a guessed name gives way to the computer's own after sign-in`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) } answers {
            f.host = f.host.copy(name = firstArg(), trustedHostKeySha256 = null); f.host
        }
        coEvery { f.screens.probeHost("host", any()) } returns f.probe.copy(hostname = "cachyos-x8664", os = "linux")
        coEvery { f.workspaces.getById(f.workspaceId) } returns workspace(f.workspaceId).copy(name = "frigate")
        coEvery { f.workspaces.isNameTaken("cachyos-x8664", f.workspaceId) } returns false
        coEvery { f.workspaces.rename(f.workspaceId, "cachyos-x8664") } returns true
        val vm = vm(f)
        vm.pickNearby(NearbyComputer("frigate", "100.64.0.5", 22, NearbySystem.UNKNOWN,
            network = NearbyNetwork.TAILSCALE, nameSource = NearbyNameSource.TAILSCALE_DNS))
        vm.signIn("ekk", "password")
        runCurrent()
        coVerify { f.workspaces.updateHost("host", "cachyos-x8664", "server.internal", 22, any(), null, null) }
        coVerify { f.workspaces.rename(f.workspaceId, "cachyos-x8664") }
    }

    @Test
    fun `a name the computer announced itself is kept`() = runTest {
        val f = Fixture()
        coEvery { f.screens.probeHost("host", any()) } returns f.probe.copy(hostname = "macbook")
        val vm = vm(f)
        vm.pickNearby(NearbyComputer("Ekk's MacBook Pro", "10.0.0.2", 22, NearbySystem.MAC, nameSource = NearbyNameSource.MDNS))
        vm.signIn("ekk", "password")
        runCurrent()
        coVerify(exactly = 0) { f.workspaces.updateHost("host", "macbook", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a saved computer that refuses its sign-in asks to sign in again`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.testHost("host") } throws failure(RemotePasswordSignInFailure.REJECTED)
        val vm = vm(f)
        runCurrent()
        vm.pickNearby(nearby(host = "server.internal"))
        runCurrent()
        assertEquals(ComputerSetupStep.SIGN_IN, vm.state.value.step)
        assertEquals("user", vm.state.value.username)
        assertNotNull(vm.state.value.error)
        assertTrue(vm.back())
        assertEquals(ComputerSetupStep.FIND, vm.state.value.step)
    }

    @Test
    fun `a saved computer that refuses the key asks for the key again`() = runTest {
        val f = Fixture()
        f.host = f.host.copy(authType = "MANAGED_KEY", sshKeyId = "key")
        coEvery { f.workspaces.testHost("host") } throws failure()
        val vm = vm(f)
        runCurrent()
        vm.pickNearby(nearby(host = "server.internal"))
        runCurrent()
        assertEquals(ComputerSetupStep.KEY, vm.state.value.step)
        assertTrue(vm.state.value.keyRefused)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a new computer does not inherit another computer's account`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.setUsername("ekk")
        assertTrue(vm.back())
        vm.pickNearby(nearby())
        assertEquals("ekk", vm.state.value.username)
        assertTrue(vm.back())
        vm.pickNearby(nearby(host = "10.0.0.3"))
        assertEquals("", vm.state.value.username)
    }

    @Test
    fun `password sign-in saves the computer, authorizes the key and switches to it`() = runTest {
        val f = Fixture()
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.signIn("ekk", "password")
        runCurrent()
        coVerify { f.workspaces.createHost("box", "10.0.0.2", 22, "ekk", RemoteAuthentication.Password("password"), null) }
        coVerify { f.workspaces.trustHostKey("host", "new fingerprint") }
        coVerify { f.workspaces.executeCommand(f.workspaceId, match { "ssh-ed25519 AAAA miffan" in it && "authorized_keys" in it }, any(), any(), any(), any(), any()) }
        coVerify { f.workspaces.updateHost("host", any(), any(), any(), "user", null, "key") }
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `password stays when the computer refuses the authorized key`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.testHost("host") } returnsMany listOf(true, false, true)
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.signIn("ekk", "password")
        runCurrent()
        coVerify { f.workspaces.updateHost("host", any(), any(), any(), any(), RemoteAuthentication.Password("password"), null) }
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
    }

    @Test
    fun `a computer without password sign-in moves to the key on the same computer`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.testHost("host") } throws failure(RemotePasswordSignInFailure.NOT_OFFERED) andThen true
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.signIn("ekk", "password")
        runCurrent()
        assertEquals(ComputerSetupStep.KEY, vm.state.value.step)
        assertTrue(vm.state.value.passwordRefused)
        assertNull(vm.state.value.error)
        vm.signInWithKey("ekk")
        runCurrent()
        coVerify(exactly = 1) { f.workspaces.createHost(any(), any(), any(), any(), any(), any()) }
        coVerify { f.workspaces.updateHost("host", any(), any(), any(), "ekk", null, "key") }
        assertEquals(ComputerSetupStep.READY, vm.state.value.step)
    }

    @Test
    fun `a wrong password is an error where it happened`() = runTest {
        val f = Fixture()
        val error = failure(RemotePasswordSignInFailure.REJECTED)
        coEvery { f.workspaces.testHost("host") } throws error
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.signIn("ekk", "wrong")
        runCurrent()
        assertEquals(ComputerSetupStep.SIGN_IN, vm.state.value.step)
        assertEquals(error, vm.state.value.error)
    }

    @Test
    fun `a key not yet authorized says so quietly and retries only a few times`() = runTest {
        val f = Fixture()
        coEvery { f.workspaces.testHost("host") } throws failure()
        val vm = vm(f)
        vm.pickNearby(nearby())
        vm.useKey()
        vm.signInWithKey("ekk")
        runCurrent()
        assertEquals(ComputerSetupStep.KEY, vm.state.value.step)
        assertTrue(vm.state.value.keyRefused)
        assertNull(vm.state.value.error)
        repeat(10) {
            vm.signInWithKey("ekk", quiet = true)
            runCurrent()
        }
        // One visible try plus four quiet ones.
        coVerify(exactly = 5) { f.workspaces.testHost("host") }
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a Mac reuses the sign-in password for Screen Sharing`() = runTest {
        val f = Fixture()
        coEvery { f.screens.probeHost("host", any()) } returns f.probe.copy(
            os = "macos", session = RemoteMachineProbe.Session(present = true, type = "quartz"),
            vnc = RemoteMachineProbe.Vnc(server = "macos-screen-sharing", running = true),
        )
        coEvery { f.registry.permissions(f.workspaceId, false) } returns null
        val vm = vm(f)
        vm.pickNearby(nearby(NearbySystem.MAC))
        vm.signIn("ekk", "password")
        runCurrent()
        coVerify { f.screens.updateConfig("host", true, RemoteScreenEndpoint.Helper, RemoteScreenAuth.MACOS_ACCOUNT, "user", "password", any(), any()) }
        assertEquals("user", vm.state.value.macAccount)
    }

    @Test
    fun `sharing binds the partners and finishes without a way back`() = runTest {
        val f = Fixture()
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        vm.continueToShare()
        runCurrent()
        assertEquals(ComputerSetupStep.SHARE, vm.state.value.step)
        val ids = setOf(Uuid.random())
        vm.bind(ids)
        runCurrent()
        coVerify { f.computers.bind("host", ids) }
        assertEquals(ComputerSetupStep.DONE, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun `bound partners follow all remote workspaces of the computer`() = runTest {
        val f = Fixture()
        val one = workspace()
        val other = workspace(hostId = "other")
        val partners = listOf(one, other).map { Assistant(workspaceId = Uuid.parse(it.id)) }
        f.workspaceList.value = listOf(one, other)
        f.settings.value = f.settings.value.copy(assistants = partners)
        val vm = vm(f, ComputerSetupArgs(hostId = "host"))
        runCurrent()
        assertEquals(setOf(partners[0].id), vm.boundPartnerIds.value)
    }

    private fun workspace(id: String = Uuid.random().toString(), hostId: String = "host") = WorkspaceEntity(
        id = id, name = "Remote", root = "remote:$id", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = hostId, remotePath = "/home/user",
    )
}
