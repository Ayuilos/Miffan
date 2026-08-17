package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class WorkspaceResourcesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `workspace root rejects current and parent directory aliases`() {
        val manager = WorkspaceManager(tmp.newFolder("root-validation"))

        listOf(".", "..", ".runtime").forEach { root ->
            assertThrows(IllegalArgumentException::class.java) {
                manager.workspaceDir(root)
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.resolveRootfsPath(root, "/tool_outputs/file.txt")
            }
        }
    }

    @Test
    fun `direct writes enforce files and rootfs quotas without blocking shrink`() {
        val manager = managerWithLimits(
            WorkspaceResourceLimits(
                maxFilesBytes = 5,
                maxRootfsBytes = 4,
                maxTempBytes = 100,
                maxWorkspaceBytes = 100,
                minFreeSpaceBytes = 0,
                maxToolOutputBytes = 10,
                maxToolOutputFileBytes = 5,
                maxShellFileBytes = 10,
            )
        )
        val root = "root"
        manager.ensureWorkspace(root)

        manager.writeText(root, "ok.txt", "12345")
        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.writeText(root, "extra.txt", "x")
        }
        manager.writeText(root, "ok.txt", "1")
        assertEquals(1, manager.diskUsage(root).filesBytes)

        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.writeRootfsText(root, "/etc/too-large", "12345")
        }
        assertFalse(manager.linuxDir(root).resolve("etc/too-large").exists())
    }

    @Test
    fun `oversized import is removed instead of leaving a partial file`() {
        val manager = managerWithLimits(
            WorkspaceResourceLimits(
                maxFilesBytes = 4,
                maxRootfsBytes = 100,
                maxTempBytes = 100,
                maxWorkspaceBytes = 100,
                minFreeSpaceBytes = 0,
                maxToolOutputBytes = 10,
                maxToolOutputFileBytes = 5,
                maxShellFileBytes = 10,
            )
        )
        val root = "root"
        manager.ensureWorkspace(root)

        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.importFile(
                root = root,
                destinationPath = "",
                fileName = "big.bin",
                inputStream = ByteArrayInputStream(ByteArray(5)),
            )
        }

        assertFalse(manager.filesDir(root).resolve("big.bin").exists())
        assertEquals(0, manager.diskUsage(root).filesBytes)
    }

    @Test
    fun `tool outputs are scoped and quota is per workspace`() {
        val outputRoot = tmp.newFolder("tool-outputs")
        val manager = managerWithLimits(
            limits = WorkspaceResourceLimits(
                maxFilesBytes = 100,
                maxRootfsBytes = 100,
                maxTempBytes = 100,
                maxWorkspaceBytes = 100,
                minFreeSpaceBytes = 0,
                maxToolOutputBytes = 6,
                maxToolOutputFileBytes = 4,
                maxShellFileBytes = 10,
            ),
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = outputRoot,
                    target = "/tool_outputs",
                    exposeToShell = false,
                    writableByTools = false,
                    workspaceScoped = true,
                )
            ),
        )
        manager.ensureWorkspace("one")
        manager.ensureWorkspace("two")

        manager.writeToolOutput("one", "first.txt", "1234")
        manager.writeToolOutput("two", "first.txt", "abcd")

        assertEquals(outputRoot.resolve("one"), manager.resolveRootfsPath("one", "/tool_outputs").rootDir)
        assertEquals(4, manager.diskUsage("one").toolOutputBytes)
        assertEquals(4, manager.diskUsage("two").toolOutputBytes)
        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.writeToolOutput("one", "second.txt", "123")
        }
        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.writeToolOutput("two", "oversized.txt", "12345")
        }
        assertFalse(outputRoot.resolve("one/second.txt").exists())
        assertFalse(outputRoot.resolve("two/oversized.txt").exists())

        manager.deleteWorkspace("one")
        assertFalse(outputRoot.resolve("one").exists())
        assertTrue(outputRoot.resolve("two/first.txt").isFile)
    }

    @Test
    fun `free space reservation fails closed before a growing write`() {
        val workspaces = tmp.newFolder("reserved-workspaces")
        val manager = WorkspaceManager(
            baseDir = workspaces,
            config = WorkspaceConfig(
                resourceLimits = WorkspaceResourceLimits(
                    maxFilesBytes = 100,
                    maxRootfsBytes = 100,
                    maxTempBytes = 100,
                    maxWorkspaceBytes = 100,
                    minFreeSpaceBytes = workspaces.usableSpace + 1,
                    maxToolOutputBytes = 10,
                    maxToolOutputFileBytes = 5,
                    maxShellFileBytes = 10,
                )
            ),
        )
        manager.ensureWorkspace("root")

        assertThrows(WorkspaceResourceLimitException::class.java) {
            manager.writeText("root", "blocked.txt", "x")
        }
    }

    @Test
    fun `session registry enforces global and per-workspace limits`() {
        val registry = WorkspaceSessionRegistry(
            WorkspaceResourceLimits(
                maxActiveSessions = 2,
                maxSessionsPerWorkspace = 1,
                sessionAcquireTimeoutMillis = 0,
            )
        )
        val first = registry.tryAcquire("one")
        val second = registry.tryAcquire("two")
        val firstLease = requireNotNull(first)
        val secondLease = requireNotNull(second)

        assertNull(registry.tryAcquire("one"))
        assertNull(registry.tryAcquire("three"))
        assertEquals(2, registry.activeSessions())
        firstLease.close()
        firstLease.close()
        assertEquals(1, registry.activeSessions())
        val thirdLease = requireNotNull(registry.tryAcquire("three"))
        secondLease.close()
        thirdLease.close()
    }

    @Test
    fun `process monitor distinguishes resource termination from timeout`() {
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 5").start()

        val result = process.readResult(
            timeoutMillis = 5_000,
            resourceGuard = WorkspaceResourceGuard {
                throw WorkspaceResourceLimitException("test quota")
            },
        )

        assertTrue(result.resourceLimitExceeded)
        assertFalse(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("test quota"))
        assertFalse(process.isAlive)
    }

    private fun managerWithLimits(
        limits: WorkspaceResourceLimits,
        bindMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceManager = WorkspaceManager(
        baseDir = tmp.newFolder("workspaces-${System.nanoTime()}"),
        config = WorkspaceConfig(resourceLimits = limits),
        bindMounts = bindMounts,
    )
}
