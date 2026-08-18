package me.rerere.workspace

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootfsHostBoundaryInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun androidHostMaintenanceUsesNativeDescriptorBackend() {
        assertTrue(usesNativeHostFileOperations())
    }

    @Test
    fun rootfsPatcherRejectsSymlinkedMaintenanceDirectory() {
        val rootfs = freshDirectory("rootfs-boundary-root")
        val outside = freshDirectory("rootfs-boundary-outside")
        val sentinel = File(outside, "hosts").apply { writeText("keep\n") }
        val etcLink = File(rootfs, "etc")
        Files.createSymbolicLink(etcLink.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher().patch(rootfs)
            }
            assertEquals("keep\n", sentinel.readText())
            assertFalse(File(outside, "hostname").exists())
        } finally {
            rootfs.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun rootfsPatcherRejectsHardLinkedMaintenanceFile() {
        val rootfs = rootfsWithEtc("rootfs-hardlink-root")
        val outside = freshDirectory("rootfs-hardlink-outside")
        val sentinel = File(outside, "hosts").apply { writeText("keep\n") }
        val hosts = File(rootfs, "etc/hosts")
        Files.createLink(hosts.toPath(), sentinel.toPath())

        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                RootfsPatcher().patch(rootfs)
            }
            assertTrue(error.message.orEmpty().contains("hard-linked"))
            assertEquals("keep\n", sentinel.readText())
        } finally {
            rootfs.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun rootfsPatcherReplacesOnlyTheExpectedResolvSymlink() {
        val rootfs = rootfsWithEtc("rootfs-resolv-root")
        val outside = freshDirectory("rootfs-resolv-outside")
        val sentinel = File(outside, "resolv.conf").apply { writeText("keep\n") }
        val resolvConf = File(rootfs, "etc/resolv.conf")
        Files.createSymbolicLink(resolvConf.toPath(), sentinel.toPath())

        try {
            RootfsPatcher().patch(
                rootfs,
                RootfsPatchOptions(
                    nameservers = listOf("9.9.9.9"),
                    groupIds = emptyList(),
                ),
            )

            assertFalse(Files.isSymbolicLink(resolvConf.toPath()))
            assertTrue(resolvConf.readText().contains("nameserver 9.9.9.9"))
            assertEquals("keep\n", sentinel.readText())
        } finally {
            rootfs.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun rootfsPatcherAppliesExactPrivateAndStickyDirectoryModes() {
        val rootfs = rootfsWithEtc("rootfs-modes-root")

        try {
            RootfsPatcher().patch(
                rootfs,
                RootfsPatchOptions(groupIds = emptyList()),
            )

            assertEquals(0b1_111_111_111, permissionBits(File(rootfs, "tmp")))
            assertEquals(0b1_111_111_111, permissionBits(File(rootfs, "var/tmp")))
            assertEquals(0b111_000_000, permissionBits(File(rootfs, "root")))
        } finally {
            rootfs.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun workspaceDeletionDoesNotTraverseGuestSymlink() {
        val baseDir = freshDirectory("delete-boundary-workspaces")
        val outside = freshDirectory("delete-boundary-outside")
        val sentinel = File(outside, "keep.txt").apply { writeText("keep") }
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        Files.createSymbolicLink(
            File(manager.filesDir(root), "escape").toPath(),
            outside.toPath(),
        )

        try {
            assertTrue(manager.deleteWorkspace(root))
            assertTrue(sentinel.isFile)
            assertEquals("keep", sentinel.readText())
        } finally {
            baseDir.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun workspaceCreationRejectsSymlinkedManagedDirectory() {
        val baseDir = freshDirectory("create-boundary-workspaces")
        val outside = freshDirectory("create-boundary-outside")
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
            baseDir.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun hostDeletionRejectsASymlinkInTheAbsoluteParentChain() {
        val container = freshDirectory("delete-parent-boundary")
        val outside = freshDirectory("delete-parent-outside")
        val victim = File(outside, "victim").apply {
            require(mkdirs())
        }
        val sentinel = File(victim, "keep.txt").apply { writeText("keep") }
        val parentLink = File(container, "parent")
        Files.createSymbolicLink(parentLink.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                File(parentLink, "victim").deleteRecursivelyNoFollow()
            }
            assertEquals("keep", sentinel.readText())
        } finally {
            container.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun hostRenameNeverReplacesAnExistingSymlink() {
        val container = freshDirectory("rename-boundary")
        val source = File(container, "source").apply { require(mkdirs()) }
        val sourceSentinel = File(source, "source.txt").apply { writeText("source") }
        val outside = freshDirectory("rename-boundary-outside")
        val outsideSentinel = File(outside, "keep.txt").apply { writeText("keep") }
        val target = File(container, "target")
        Files.createSymbolicLink(target.toPath(), outside.toPath())

        try {
            assertThrows(IllegalArgumentException::class.java) {
                source.renameDirectoryNoFollow(target)
            }
            assertEquals("source", sourceSentinel.readText())
            assertTrue(Files.isSymbolicLink(target.toPath()))
            assertEquals("keep", outsideSentinel.readText())

            Files.delete(target.toPath())
            assertTrue(source.renameDirectoryNoFollow(target))
            assertFalse(Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("source", File(target, "source.txt").readText())
        } finally {
            container.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun directFileToolsPreserveUploadsAndRejectLinkEscapes() {
        val baseDir = freshDirectory("file-tools-boundary")
        val upload = freshDirectory("file-tools-upload")
        val outside = freshDirectory("file-tools-outside")
        val manager = WorkspaceManager(
            baseDir = baseDir,
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = upload,
                    target = "/upload",
                    exposeToShell = false,
                    writableByTools = false,
                )
            ),
        )
        val root = "root"
        manager.ensureWorkspace(root)
        File(upload, "note.txt").writeText("uploaded")
        val output = ByteArrayOutputStream()

        try {
            assertEquals(8L, manager.rootfsFileSize(root, "/upload/note.txt"))
            manager.exportRootfsFile(root, "/upload/note.txt", output)
            assertEquals("uploaded", output.toString(Charsets.UTF_8.name()))

            File(upload, "bounded.txt").writeText("12345")
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceFileSystem().exportNoFollow(
                    root = upload,
                    path = "bounded.txt",
                    outputStream = ByteArrayOutputStream(),
                    maxBytes = 4,
                )
            }

            manager.writeRootfsText(root, "/workspace/existing.txt", "original")
            assertThrows(IllegalArgumentException::class.java) {
                manager.writeRootfsText(
                    root,
                    "/workspace/existing.txt",
                    "replacement",
                    overwrite = false,
                )
            }
            assertEquals("original", File(manager.filesDir(root), "existing.txt").readText())

            val outsideSentinel = File(outside, "keep.txt").apply { writeText("keep") }
            Files.createSymbolicLink(
                File(manager.filesDir(root), "escape").toPath(),
                outside.toPath(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                manager.writeRootfsText(root, "/workspace/escape/evil.txt", "evil")
            }
            assertFalse(File(outside, "evil.txt").exists())

            Files.createSymbolicLink(
                File(upload, "escape.txt").toPath(),
                outsideSentinel.toPath(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                manager.rootfsFileSize(root, "/upload/escape.txt")
            }
            assertEquals("keep", outsideSentinel.readText())
        } finally {
            baseDir.deleteRecursivelyNoFollow()
            upload.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun workspaceMutationsUseNativeDescriptors() {
        val baseDir = freshDirectory("mutation-boundary")
        val outside = freshDirectory("mutation-outside")
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        val sentinel = File(outside, "keep.txt").apply { writeText("keep") }

        try {
            val imported = manager.importFile(
                root = root,
                destinationPath = "imports",
                fileName = "note.txt",
                inputStream = ByteArrayInputStream("imported".toByteArray()),
            )
            assertEquals("imports/note.txt", imported.path)
            val conflict = manager.importFile(
                root = root,
                destinationPath = "imports",
                fileName = "note.txt",
                inputStream = ByteArrayInputStream("second".toByteArray()),
            )
            assertEquals("imports/note (1).txt", conflict.path)

            val moved = manager.moveFile(root, imported.path, "moved/note.txt")
            assertEquals("moved/note.txt", moved.path)
            assertEquals("imported", File(manager.filesDir(root), moved.path).readText())

            val overwriteTarget = File(manager.filesDir(root), "replace.txt")
            Files.createSymbolicLink(overwriteTarget.toPath(), sentinel.toPath())
            manager.moveFile(root, conflict.path, "replace.txt", overwrite = true)
            assertFalse(Files.isSymbolicLink(overwriteTarget.toPath()))
            assertEquals("second", overwriteTarget.readText())
            assertEquals("keep", sentinel.readText())

            val importEscape = File(manager.filesDir(root), "escape")
            Files.createSymbolicLink(importEscape.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                manager.importFile(
                    root = root,
                    destinationPath = "escape",
                    fileName = "evil.txt",
                    inputStream = ByteArrayInputStream("evil".toByteArray()),
                )
            }
            assertFalse(File(outside, "evil.txt").exists())

            assertTrue(manager.deleteFile(root, "escape", recursive = true))
            assertEquals("keep", sentinel.readText())
            File(manager.filesDir(root), "directory/child").apply {
                parentFile?.mkdirs()
                writeText("child")
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.deleteFile(root, "directory", recursive = false)
            }
            assertTrue(manager.deleteFile(root, "directory", recursive = true))
        } finally {
            baseDir.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun workspaceExportsUseNativeStreamingDescriptors() {
        val baseDir = freshDirectory("export-boundary")
        val outside = freshDirectory("export-boundary-outside")
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        val content = ByteArray(8 * 1024 * 1024 + 1) { (it % 251).toByte() }
        File(manager.filesDir(root), "large.bin").writeBytes(content)
        val exported = ByteArrayOutputStream(content.size)
        val sentinel = File(outside, "secret.bin").apply { writeText("secret") }
        Files.createSymbolicLink(
            File(manager.filesDir(root), "escape.bin").toPath(),
            sentinel.toPath(),
        )

        try {
            assertEquals(content.size.toLong(), manager.fileSize(root, "large.bin"))
            manager.exportFile(root, "large.bin", outputStream = exported)
            assertArrayEquals(content, exported.toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                manager.fileSize(root, "escape.bin")
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.exportFile(root, "escape.bin", outputStream = ByteArrayOutputStream())
            }
            assertEquals("secret", sentinel.readText())
        } finally {
            baseDir.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun workspaceDiscoveryUsesNativeDirectoryDescriptors() {
        val baseDir = freshDirectory("discovery-boundary")
        val outside = freshDirectory("discovery-boundary-outside")
        val manager = WorkspaceManager(baseDir)
        val root = "root"
        manager.ensureWorkspace(root)
        File(manager.filesDir(root), "目录/笔记.txt").apply {
            parentFile?.mkdirs()
            writeText("safe needle")
        }
        val sentinel = File(outside, "secret.txt").apply { writeText("outside needle") }
        Files.createSymbolicLink(
            File(manager.filesDir(root), "leak.txt").toPath(),
            sentinel.toPath(),
        )
        Files.createSymbolicLink(
            File(manager.filesDir(root), "escape").toPath(),
            outside.toPath(),
        )

        try {
            assertEquals(listOf("目录"), manager.listFiles(root).map { it.path })
            assertEquals(
                listOf("目录/笔记.txt"),
                manager.glob(root, "**/*.txt").map { it.path },
            )
            assertEquals(
                listOf(WorkspaceSearchMatch("目录/笔记.txt", 1, "safe needle")),
                manager.grep(root, "needle"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                manager.listFiles(root, "escape")
            }
            assertEquals("outside needle", sentinel.readText())
        } finally {
            baseDir.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    @Test
    fun rootfsExtractionUsesNativeDescriptorSink() {
        val rootfs = freshDirectory("extraction-boundary")
        val outside = freshDirectory("extraction-boundary-outside")
        val sentinel = File(outside, "keep.txt").apply { writeText("keep") }
        val files = rootfsExtractionFiles(rootfs)

        try {
            files.directory("etc")
            files.symlink("etc/config", sentinel.absolutePath)
            files.openFile("etc/config", 0b110_100_100).use { output ->
                output.write("replacement".toByteArray())
            }
            assertFalse(Files.isSymbolicLink(File(rootfs, "etc/config").toPath()))
            assertEquals("replacement", File(rootfs, "etc/config").readText())
            assertEquals("keep", sentinel.readText())

            files.symlink("escape", outside.absolutePath)
            assertThrows(IllegalArgumentException::class.java) {
                files.openFile("escape/evil.txt", 0b110_100_100).use { output ->
                    output.write("evil".toByteArray())
                }
            }
            assertFalse(File(outside, "evil.txt").exists())

            files.openFile("source", 0b110_100_100).use { output ->
                output.write("data".toByteArray())
            }
            assertEquals(4L, files.hardLink("linked", "source"))
            assertEquals(
                Os.stat(File(rootfs, "source").path).st_ino,
                Os.stat(File(rootfs, "linked").path).st_ino,
            )
        } finally {
            rootfs.deleteRecursivelyNoFollow()
            outside.deleteRecursivelyNoFollow()
        }
    }

    private fun freshDirectory(name: String): File =
        File(context.cacheDir, "$name-${System.nanoTime()}").also { directory ->
            directory.deleteRecursivelyNoFollow()
            require(directory.mkdirs()) { "Unable to create test directory: $directory" }
        }

    private fun rootfsWithEtc(name: String): File = freshDirectory(name).also { rootfs ->
        require(File(rootfs, "etc").mkdirs()) { "Unable to create Rootfs /etc" }
    }

    private fun permissionBits(file: File): Int = Os.stat(file.absolutePath).st_mode and 0xFFF
}
