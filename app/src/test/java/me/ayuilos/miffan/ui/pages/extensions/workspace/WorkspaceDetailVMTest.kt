package me.ayuilos.miffan.ui.pages.extensions.workspace

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.repository.RemoteHostRuntimeState
import me.ayuilos.miffan.data.repository.RemoteWorkspaceRuntimeState
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDetailVMTest {
    private val workspace = WorkspaceEntity(
        id = "ws", name = "Remote", root = "remote:ws", createdAt = 1, updatedAt = 1,
        kind = WorkspaceEntity.KIND_REMOTE, remoteHostId = "host", remotePath = "/srv/project",
    )
    private val host = RemoteHostEntity(
        id = "host", name = "Server", host = "server.internal", port = 22,
        username = "user", authType = "PASSWORD", trustedHostKeySha256 = "fingerprint",
        createdAt = 1, updatedAt = 1,
    )

    private fun repository(): WorkspaceRepository = mockk<WorkspaceRepository>().also { repository ->
        every { repository.remoteHostStates } returns MutableStateFlow<Map<String, RemoteHostRuntimeState>>(emptyMap())
        every { repository.remoteWorkspaceStates } returns MutableStateFlow<Map<String, RemoteWorkspaceRuntimeState>>(emptyMap())
        every { repository.remoteConnectionStates } returns MutableStateFlow(emptyMap())
        coEvery { repository.getById("ws") } returns workspace
        coEvery { repository.getHostById("host") } returns host
    }

    private fun entry(path: String) = WorkspaceFileEntry(
        path = path, name = path.substringAfterLast('/'), isDirectory = false,
        sizeBytes = 1, updatedAt = 1,
    )

    @Test
    fun `first load uses deep link path once and redirects remote rootfs area before listing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "nested", null) } returns listOf(entry("nested/report.txt"))
            val vm = WorkspaceDetailVM(
                WorkspaceDetailArgs("ws", initialArea = WorkspaceStorageArea.LINUX, initialPath = "/nested/"),
                repository, mockk<SkillManager>(), mockk(relaxed = true),
            )
            runCurrent()

            assertEquals(WorkspaceStorageArea.FILES, vm.state.value.area)
            assertEquals("nested", vm.state.value.path)
            assertEquals(listOf(entry("nested/report.txt")), vm.state.value.entries)
            assertFalse(vm.state.value.loading)
            vm.navigateTo(WorkspaceStorageArea.LINUX, "nested") // Page's first effect must be a no-op.
            runCurrent()
            coVerify(exactly = 1) { repository.listFiles("ws", WorkspaceStorageArea.FILES, "nested", null) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `page initial navigation does not cancel an in progress first connection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releaseConnection = CompletableDeferred<Unit>()
        try {
            val repository = repository()
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "nested", null) } coAnswers {
                releaseConnection.await()
                listOf(entry("nested/report.txt"))
            }
            val vm = WorkspaceDetailVM(WorkspaceDetailArgs("ws", initialPath = "nested"), repository, mockk<SkillManager>(), mockk(relaxed = true))
            runCurrent()
            assertTrue(vm.state.value.loading)
            vm.navigateTo(WorkspaceStorageArea.FILES, "nested")
            runCurrent()
            coVerify(exactly = 1) { repository.listFiles("ws", WorkspaceStorageArea.FILES, "nested", null) }
            releaseConnection.complete(Unit)
            runCurrent()
            assertEquals(listOf(entry("nested/report.txt")), vm.state.value.entries)
            assertEquals(null, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally {
            releaseConnection.complete(Unit)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late failure from cancelled directory cannot overwrite newer contents`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            val releaseOld = CompletableDeferred<Unit>()
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "old", null) } coAnswers {
                withContext(NonCancellable) { releaseOld.await() }
                throw IOException("stale SSH failure")
            }
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "new", null) } returns listOf(entry("new/current.txt"))
            val vm = WorkspaceDetailVM(WorkspaceDetailArgs("ws", initialPath = "old"), repository, mockk<SkillManager>(), mockk(relaxed = true))
            runCurrent()
            assertTrue(vm.state.value.loading)

            vm.navigateTo(WorkspaceStorageArea.FILES, "new")
            runCurrent()
            assertEquals(listOf(entry("new/current.txt")), vm.state.value.entries)
            releaseOld.complete(Unit)
            runCurrent()
            assertEquals(listOf(entry("new/current.txt")), vm.state.value.entries)
            assertEquals(null, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late success from cancelled directory cannot overwrite newer contents`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            val releaseOld = CompletableDeferred<Unit>()
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "old", null) } coAnswers {
                withContext(NonCancellable) { releaseOld.await() }
                listOf(entry("old/stale.txt"))
            }
            coEvery { repository.listFiles("ws", WorkspaceStorageArea.FILES, "new", null) } returns listOf(entry("new/current.txt"))
            val vm = WorkspaceDetailVM(WorkspaceDetailArgs("ws", initialPath = "old"), repository, mockk<SkillManager>(), mockk(relaxed = true))
            runCurrent()

            vm.navigateTo(WorkspaceStorageArea.FILES, "new")
            runCurrent()
            releaseOld.complete(Unit)
            runCurrent()
            assertEquals(listOf(entry("new/current.txt")), vm.state.value.entries)
            assertFalse(vm.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `metadata failure exits loading and shows an error without listing files`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            coEvery { repository.getById("ws") } throws IOException("metadata unavailable")
            val vm = WorkspaceDetailVM(WorkspaceDetailArgs("ws"), repository, mockk<SkillManager>(), mockk(relaxed = true))
            runCurrent()

            assertFalse(vm.state.value.loading)
            assertEquals("metadata unavailable", vm.state.value.error)
            coVerify(exactly = 0) { repository.listFiles(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `terminal entry loads only workspace metadata and never lists files`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            val vm = WorkspaceDetailVM(
                WorkspaceDetailArgs("ws", loadFilesInitially = false),
                repository, mockk<SkillManager>(), mockk(relaxed = true),
            )
            runCurrent()

            assertEquals(workspace, vm.state.value.workspace)
            assertEquals(host, vm.state.value.remoteHost)
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
            coVerify(exactly = 0) { repository.listFiles(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `terminal metadata failure stops loading and can retry without listing files`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = repository()
            var attempts = 0
            coEvery { repository.getById("ws") } coAnswers {
                if (attempts++ == 0) throw IOException("metadata unavailable")
                workspace
            }
            val vm = WorkspaceDetailVM(
                WorkspaceDetailArgs("ws", loadFilesInitially = false),
                repository, mockk<SkillManager>(), mockk(relaxed = true),
            )
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals("metadata unavailable", vm.state.value.error)

            vm.reloadMetadataOnly()
            runCurrent()
            assertEquals(workspace, vm.state.value.workspace)
            assertFalse(vm.state.value.loading)
            assertEquals(null, vm.state.value.error)
            coVerify(exactly = 0) { repository.listFiles(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
