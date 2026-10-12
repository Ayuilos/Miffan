package me.ayuilos.miffan.data.repository

import android.content.Context
import android.content.res.AssetManager
import io.mockk.*
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.db.dao.RemoteHostDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test

class WindowsScreenRepositoryTest {
    private val host = RemoteHostEntity("host", "Windows", "100.64.0.8", 22, "test", "KEY",
        trustedHostKeySha256 = "pin", createdAt = 0, updatedAt = 0, screenEnabled = true,
        screenEndpoint = "legacy-invalid-endpoint", platform = "posix", streamEnabled = false)
    private val workspace = WorkspaceEntity("ws", "test", "remote:ws", createdAt = 0, updatedAt = 0,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/C:/Users/test")
    private val dao = mockk<WorkspaceDAO>()
    private val hosts = mockk<RemoteHostDAO>()
    private val ssh = mockk<RemoteWorkspaceSession>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val commands = mutableListOf<String>()
    private var approvedStdin: String? = null
    private var firewallExitCode = 0
    private var sunshineInstalled = true
    private var installedVersion = "6"
    private var installedBytes: ByteArray? = null
    private var firewall = WindowsScreenHelperTest.reviewJson
    private fun repo(): RemoteScreenRepository {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(android.net.ConnectivityManager::class.java) } returns mockk(relaxed = true)
        every { context.noBackupFilesDir } returns File("build/test-p7b2")
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } answers { File("src/main/assets/" + firstArg<String>()).inputStream() }
        coEvery { dao.getById("ws") } returns workspace
        coEvery { dao.getByRemoteHostId("host") } returns listOf(workspace)
        coEvery { hosts.getById("host") } returns host
        every { ssh.platform } returns RemotePlatform.WINDOWS
        every { ssh.execute(any(), any(), any(), any(), any()) } answers {
            val command = firstArg<String>(); commands += command
            val output = when {
                command == windowsHelperVersionCommand -> installedVersion
                command == windowsHelperInstallCommand -> { installedBytes = arg<InputStream?>(4)!!.readBytes(); installedVersion = "6"; "" }
                command.endsWith("sunshine firewall check") -> firewall
                command.endsWith("sunshine firewall apply") -> {
                    approvedStdin = arg<InputStream?>(4)!!.readBytes().toString(Charsets.UTF_8)
                    """{"success":true,"detail":"applied","backup":"C:\\backup","changed":true,"firewall_model_ok":true}"""
                }
                command.endsWith("sunshine probe") -> """{"installed":$sunshineInstalled,"running":true,"active_stream":false,"lan_encryption_mode":2,"wan_encryption_mode":2}"""
                command.endsWith("cua install") -> """{"success":true,"version":"0.34.0","autostart_run_level":"Limited"}"""
                command.endsWith(" probe") -> """{"helper":2,"os":"windows","arch":"AMD64","session":{"present":true,"type":"console"},"cua":{"min":"0.34.0","ok":false},"vnc":{}}"""
                else -> error("Unexpected command: $command")
            }
            WorkspaceCommandResult(if (command.endsWith("sunshine firewall check")) firewallExitCode else 0, output, "")
        }
        coEvery { workspaces.openLeasedRemote<Closeable>(any(), any()) } answers {
            LeasedRemote(secondArg<(RemoteWorkspaceSession) -> Closeable>()(ssh)) {}
        }
        return RemoteScreenRepository(workspaces, dao, hosts, mockk(), assets, context)
    }
    @Test fun actualSftpWindowsOverridesStaleHostAndDisabledOrSkippedStreamAndNeverFallsBack() = runBlocking {
        val repository = repo()
        val failure = runCatching { repository.open("ws", mockk(), null, stream = RemoteStreamRequest(skip = true)) }.exceptionOrNull()
        assertEquals(RemoteScreenProblem.FIREWALL_REVIEW_NEEDED, (failure as RemoteScreenUnavailableException).problem)
        assertTrue(commands.any { it.endsWith("sunshine firewall check") })
        assertFalse(commands.any { "apply" in it || "vnc" in it || "rdp" in it || "uname" in it })
    }
    @Test fun checkCannotApplyAndExplicitApprovedApplySendsExactStdinOnce() = runBlocking {
        val repository = repo()
        val check = repository.checkSunshineFirewall("host", "legacy")
        assertNull(approvedStdin)
        val approved = check.suggestedPlan!!.approve()
        val result = repository.applySunshineFirewall("host", "legacy", approved)
        assertTrue(result.success); assertEquals("C:\\backup", result.backup)
        assertEquals(approved.json, approvedStdin)
        assertEquals(1, commands.count { it.endsWith("sunshine firewall apply") })
    }
    @Test fun failedInspectionRetainsHelperDetailAndCannotOpenEvenIfJsonClaimsSafe() = runBlocking {
        val repository = repo()
        firewallExitCode = 1
        firewall = """{"success":true,"detail":"Cannot inspect firewall policy","firewall_model_ok":true}"""
        val result = repository.checkSunshineFirewall("host", "legacy")
        assertFalse(result.success)
        val error = runCatching { repository.open("ws", mockk(), null) }.exceptionOrNull() as RemoteScreenUnavailableException
        assertEquals(RemoteScreenProblem.FIREWALL_CHECK_FAILED, error.problem)
        assertEquals("Cannot inspect firewall policy", error.detail)
    }
    @Test fun missingSunshineMapsToSpecificProblemWithoutRdpOrVncFallback() = runBlocking {
        val repository = repo()
        sunshineInstalled = false
        firewall = """{"success":true,"detail":"safe","firewall_model_ok":true}"""
        val error = runCatching { repository.open("ws", mockk(), null) }.exceptionOrNull() as RemoteScreenUnavailableException
        assertEquals(RemoteScreenProblem.STREAM_UNAVAILABLE, error.problem)
        assertEquals(RemoteStreamFallbackReason.SUNSHINE_MISSING, error.streamFailure!!.reason)
        assertFalse(commands.any { "vnc" in it || "rdp" in it })
    }
    @Test fun helperUpgradeTransfersExactBomBytesOnlyOnceAndManualWindowsSettingsAreInapplicable() = runBlocking {
        val repository = repo()
        installedVersion = "1"
        repository.checkSunshineFirewall("host", "legacy")
        assertArrayEquals(File("src/main/assets/remote/miffan.ps1").readBytes(), installedBytes)
        repository.checkSunshineFirewall("host", "legacy")
        assertEquals(1, commands.count { it == windowsHelperInstallCommand })
        val result = repository.openSunshinePermissionSettings("host", "legacy", RemoteSunshinePermission.SCREEN_RECORDING)
        assertFalse(result.success)
        assertFalse(commands.any { "open-settings" in it || "wake" in it })
    }
    @Test fun cuaUsesPowerShellHelperAndLeasedStdioWithConfirmedInstall() = runBlocking {
        val repository = repo()
        val stream = mockk<me.rerere.workspace.RemoteChannelStream>(relaxed = true)
        every { ssh.openProcess(any()) } returns stream
        val handle = repository.openCuaProcess("ws")
        assertSame(stream, handle.value)
        verify(exactly = 1) { ssh.openProcess(screenHelperCommand(RemotePlatform.WINDOWS, "cua")) }
        handle.close()
        assertTrue(repository.installCuaDriver("ws", "C:/old").success)
        assertEquals(1, commands.count { it.endsWith("cua install") })
        assertFalse(commands.any { "/bin/bash" in it || "systemctl" in it })
    }
    @Test fun revisionChangeIsRejectedBeforeCommands() = runBlocking {
        val repository = repo()
        assertTrue(runCatching { repository.checkSunshineFirewall("host", "stale") }.exceptionOrNull() is WorkspaceToolTargetChangedException)
        assertTrue(commands.isEmpty())
    }
}
