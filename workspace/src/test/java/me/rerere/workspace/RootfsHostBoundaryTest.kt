package me.rerere.workspace

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsHostBoundaryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `patcher rejects a symlinked etc directory without touching its target`() {
        val rootfs = tmp.newFolder("symlinked-etc-rootfs")
        val outside = tmp.newFolder("symlinked-etc-outside")
        val outsideHosts = File(outside, "hosts").apply { writeText("outside\n") }
        val etcLink = File(rootfs, "etc")
        Files.createSymbolicLink(etcLink.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher(NioRootfsPatchFilesFactory).patch(rootfs)
            }
            assertEquals("outside\n", outsideHosts.readText())
            assertFalse(File(outside, "hostname").exists())
        } finally {
            Files.deleteIfExists(etcLink.toPath())
        }
    }

    @Test
    fun `patcher rejects a maintenance file symlink without modifying its target`() {
        val rootfs = rootfsWithEtc("symlinked-hosts-rootfs")
        val outside = tmp.newFile("outside-hosts").apply { writeText("do-not-change\n") }
        val hostsLink = File(rootfs, "etc/hosts")
        Files.createSymbolicLink(hostsLink.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher(NioRootfsPatchFilesFactory).patch(rootfs)
            }
            assertEquals("do-not-change\n", outside.readText())
        } finally {
            Files.deleteIfExists(hostsLink.toPath())
        }
    }

    @Test
    fun `patcher rejects a hard-linked maintenance file without modifying its peer`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
        val rootfs = rootfsWithEtc("hard-linked-hosts-rootfs")
        val outside = tmp.newFile("outside-hard-linked-hosts").apply {
            writeText("do-not-change\n")
        }
        val hosts = File(rootfs, "etc/hosts")
        Files.createLink(hosts.toPath(), outside.toPath())

        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher(NioRootfsPatchFilesFactory).patch(rootfs)
            }
            assertTrue(error.message.orEmpty().contains("hard-linked"))
            assertEquals("do-not-change\n", outside.readText())
        } finally {
            Files.deleteIfExists(hosts.toPath())
        }
    }

    @Test
    fun `patcher replaces the expected resolv symlink without following it`() {
        val rootfs = rootfsWithEtc("resolv-symlink-rootfs")
        val outside = tmp.newFile("outside-resolv").apply { writeText("do-not-change\n") }
        val resolvConf = File(rootfs, "etc/resolv.conf")
        Files.createSymbolicLink(resolvConf.toPath(), outside.toPath())

        RootfsPatcher(NioRootfsPatchFilesFactory).patch(
            rootfs,
            RootfsPatchOptions(nameservers = listOf("9.9.9.9"), groupIds = emptyList()),
        )

        assertFalse(Files.isSymbolicLink(resolvConf.toPath()))
        assertTrue(resolvConf.readText().contains("nameserver 9.9.9.9"))
        assertEquals("do-not-change\n", outside.readText())
    }

    @Test
    fun `patcher never changes permissions through a temp directory symlink`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val rootfs = rootfsWithEtc("symlinked-temp-rootfs")
        val outside = tmp.newFolder("symlinked-temp-outside")
        val ownerOnly = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        Files.setPosixFilePermissions(outside.toPath(), ownerOnly)
        val tmpLink = File(rootfs, "tmp")
        Files.createSymbolicLink(tmpLink.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher(NioRootfsPatchFilesFactory).patch(rootfs)
            }
            assertEquals(ownerOnly, Files.getPosixFilePermissions(outside.toPath()))
        } finally {
            Files.deleteIfExists(tmpLink.toPath())
            Files.setPosixFilePermissions(
                outside.toPath(),
                ownerOnly +
                    PosixFilePermission.GROUP_READ +
                    PosixFilePermission.GROUP_EXECUTE +
                    PosixFilePermission.OTHERS_READ +
                    PosixFilePermission.OTHERS_EXECUTE,
            )
        }
    }

    @Test
    fun `patcher bounds guest controlled maintenance files and option fields`() {
        val rootfs = rootfsWithEtc("bounded-patch-rootfs")
        File(rootfs, "etc/hosts").writeBytes(ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() })

        val fileError = assertThrows(IllegalArgumentException::class.java) {
            RootfsPatcher(NioRootfsPatchFilesFactory).patch(rootfs)
        }
        assertTrue(fileError.message.orEmpty().contains("too large"))

        val optionError = assertThrows(IllegalArgumentException::class.java) {
            RootfsPatcher(NioRootfsPatchFilesFactory).patch(
                rootfs,
                RootfsPatchOptions(hostname = "safe\ninjected"),
            )
        }
        assertTrue(optionError.message.orEmpty().contains("hostname"))
    }

    @Test
    fun `workspace cleanup and deletion never traverse guest symlinks`() {
        val baseDir = tmp.newFolder("nofollow-workspaces")
        val outside = tmp.newFolder("nofollow-outside")
        val sentinel = File(outside, "keep.txt").apply { writeText("keep") }
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "var").mkdirs()
        val linuxTmp = File(manager.linuxDir(root), "tmp")
        val linuxVarTmp = File(manager.linuxDir(root), "var/tmp")
        val filesEscape = File(manager.filesDir(root), "escape")
        listOf(linuxTmp, linuxVarTmp, filesEscape).forEach { link ->
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }

        manager.cleanupAllTempDirs()

        assertTrue(sentinel.isFile)
        assertFalse(Files.exists(linuxTmp.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(linuxVarTmp.toPath(), LinkOption.NOFOLLOW_LINKS))

        assertTrue(manager.deleteWorkspace(root))
        assertTrue(sentinel.isFile)
        assertFalse(Files.exists(filesEscape.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `workspace maintenance rejects symlinked parent directories`() {
        val baseDir = tmp.newFolder("nofollow-parent-workspaces")
        val outside = tmp.newFolder("nofollow-parent-outside")
        val outsideTmp = File(outside, "tmp").apply { mkdirs() }
        val sentinel = File(outsideTmp, "keep.txt").apply { writeText("keep") }
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        val rootfsVar = File(manager.linuxDir(root), "var")
        Files.createSymbolicLink(rootfsVar.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                manager.cleanupAllTempDirs()
            }
            assertEquals("keep", sentinel.readText())
        } finally {
            Files.deleteIfExists(rootfsVar.toPath())
        }
    }

    @Test
    fun `ensure workspace rejects a symlinked managed directory`() {
        val baseDir = tmp.newFolder("nofollow-ensure-workspaces")
        val outside = tmp.newFolder("nofollow-ensure-outside")
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        val filesDir = manager.filesDir(root)
        filesDir.deleteRecursivelyNoFollow()
        Files.createSymbolicLink(filesDir.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                manager.ensureWorkspace(root)
            }
            assertFalse(File(outside, "linux").exists())
        } finally {
            Files.deleteIfExists(filesDir.toPath())
        }
    }

    @Test
    fun `rootfs health rejects entrypoints resolving outside the root`() {
        val rootfs = rootfsWithEtc("health-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "bin/bash").writeText("bash")
        File(rootfs, "usr/bin/env").writeText("env")
        val outsideShell = tmp.newFile("outside-shell").apply { writeText("shell") }
        val shell = File(rootfs, "bin/sh")
        Files.createSymbolicLink(shell.toPath(), outsideShell.toPath())

        assertFalse(RootfsHealth.isHealthy(rootfs))

        Files.delete(shell.toPath())
        Files.createSymbolicLink(shell.toPath(), Path.of("bash"))
        assertTrue(RootfsHealth.isHealthy(rootfs))
    }

    private fun rootfsWithEtc(name: String): File = tmp.newFolder(name).also { rootfs ->
        File(rootfs, "etc").mkdirs()
    }
}
