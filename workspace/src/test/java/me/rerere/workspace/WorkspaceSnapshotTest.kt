package me.rerere.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSnapshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `snapshot round trip replaces files exactly`() {
        val source = WorkspaceManager(tmp.newFolder("source"))
        val destination = WorkspaceManager(tmp.newFolder("destination"))
        source.ensureWorkspace("root")
        destination.ensureWorkspace("root")
        source.writeText("root", "dir/text.txt", "hello")
        val binary = byteArrayOf(0, 1, 2, -1)
        source.importFile("root", "dir", fileName = "binary.bin", inputStream = binary.inputStream())
        destination.writeText("root", "old.txt", "remove me")

        val snapshot = ByteArrayOutputStream().also { source.exportFilesSnapshot("root", it) }.toByteArray()
        destination.replaceFilesSnapshot("root", snapshot.inputStream())

        assertEquals("hello", destination.readText("root", "dir/text.txt"))
        val output = ByteArrayOutputStream()
        destination.exportFile("root", "dir/binary.bin", outputStream = output)
        assertArrayEquals(binary, output.toByteArray())
        assertFalse(destination.filesDir("root").resolve("old.txt").exists())
    }

    @Test
    fun `invalid snapshot path rolls back previous files`() {
        val manager = WorkspaceManager(tmp.newFolder("rollback"))
        manager.ensureWorkspace("root")
        manager.writeText("root", "keep.txt", "keep")
        val malformed = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x524B5753)
                output.writeInt(1)
                output.writeByte(2)
                val path = "../escape".toByteArray()
                output.writeInt(path.size)
                output.write(path)
                output.writeLong(4)
                output.write("evil".toByteArray())
                output.writeByte(0)
            }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            manager.replaceFilesSnapshot("root", ByteArrayInputStream(malformed))
        }

        assertEquals("keep", manager.readText("root", "keep.txt"))
        assertFalse(tmp.root.resolve("escape").exists())
    }

    @Test
    fun `snapshot export refuses symbolic links`() {
        val manager = WorkspaceManager(tmp.newFolder("symlink"))
        manager.ensureWorkspace("root")
        val outside = tmp.newFile("outside").apply { writeText("secret") }
        val link = manager.filesDir("root").resolve("link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            manager.exportFilesSnapshot("root", ByteArrayOutputStream())
        }
    }

    @Test
    fun `manager startup restores an interrupted snapshot swap`() {
        val base = tmp.newFolder("startup-recovery")
        val original = WorkspaceManager(base)
        original.ensureWorkspace("root")
        original.writeText("root", "keep.txt", "keep")
        val workspace = original.workspaceDir("root")
        val files = original.filesDir("root")
        val backup = workspace.resolve("files-backup")
        val staging = workspace.resolve("files-staging")
        assertTrue(files.renameTo(backup))
        assertTrue(files.mkdirs())
        files.resolve("uncommitted.txt").writeText("discard")
        assertTrue(staging.mkdirs())
        staging.resolve("partial.txt").writeText("discard")

        val recovered = WorkspaceManager(base)

        assertEquals("keep", recovered.readText("root", "keep.txt"))
        assertFalse(recovered.filesDir("root").resolve("uncommitted.txt").exists())
        assertFalse(backup.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `manager startup keeps a committed snapshot while cleaning its old backup`() {
        val base = tmp.newFolder("committed-recovery")
        val original = WorkspaceManager(base)
        original.ensureWorkspace("root")
        original.writeText("root", "old.txt", "discard")
        val workspace = original.workspaceDir("root")
        val files = original.filesDir("root")
        val committedBackup = workspace.resolve("files-backup-committed")
        assertTrue(files.renameTo(committedBackup))
        assertTrue(files.mkdirs())
        files.resolve("new.txt").writeText("keep")

        val recovered = WorkspaceManager(base)

        assertEquals("keep", recovered.readText("root", "new.txt"))
        assertFalse(recovered.filesDir("root").resolve("old.txt").exists())
        assertFalse(committedBackup.exists())
    }
}
