package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
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

    private fun freshDirectory(name: String): File =
        File(context.cacheDir, "$name-${System.nanoTime()}").also { directory ->
            directory.deleteRecursivelyNoFollow()
            require(directory.mkdirs()) { "Unable to create test directory: $directory" }
        }
}
