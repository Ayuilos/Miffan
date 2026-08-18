package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `extract enforces expanded byte quota`() {
        val archive = tmp.newFile("quota.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("too-large.txt", '0', "12345".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("quota-out")
        val installer = RootfsInstaller(
            manager = WorkspaceManager(tmp.newFolder("quota-workspaces")),
            limits = RootfsInstallLimits(maxExtractedBytes = 4),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            installer.extractTar(archive, target) {}
        }

        assertTrue(error.message!!.contains("extracted size exceeds limit"))
    }

    @Test
    fun `download enforces archive byte quota before extraction`() {
        val archive = ByteArray(5) { 1 }
        val manager = WorkspaceManager(tmp.newFolder("download-quota-workspaces"))
        val installer = RootfsInstaller(
            manager = manager,
            limits = RootfsInstallLimits(maxDownloadBytes = 4),
            connectionFactory = { TestHttpURLConnection(it, archive) },
        )
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            installer.install("root", source)
        }

        assertTrue(error.message!!.contains("download limit"))
        assertFalse(manager.hasRootfs("root"))
    }

    @Test
    fun `download never follows a replaced archive symlink`() {
        val archive = ByteArray(8) { 1 }
        val manager = WorkspaceManager(tmp.newFolder("download-symlink-workspaces"))
        val root = "root"
        val outside = tmp.newFile("download-symlink-outside").apply { writeText("keep") }
        val archiveTarget = File(manager.tempDir(root), "rootfs.tar.gz")
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )
        val installer = RootfsInstaller(
            manager = manager,
            connectionFactory = {
                TestHttpURLConnection(it, archive) {
                    Files.createSymbolicLink(archiveTarget.toPath(), outside.toPath())
                }
            },
        )

        assertThrows(IOException::class.java) {
            installer.install(root, source)
        }

        assertEquals("keep", outside.readText())
        assertFalse(Files.exists(archiveTarget.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `install reserves workspace temp capacity before download`() {
        val manager = WorkspaceManager(
            baseDir = tmp.newFolder("reserved-install-workspaces"),
            config = WorkspaceConfig(
                resourceLimits = WorkspaceResourceLimits(
                    maxFilesBytes = 100,
                    maxRootfsBytes = 100,
                    maxTempBytes = 4,
                    maxWorkspaceBytes = 100,
                    minFreeSpaceBytes = 0,
                    maxToolOutputBytes = 10,
                    maxToolOutputFileBytes = 5,
                    maxShellFileBytes = 10,
                )
            ),
        )
        val archive = ByteArray(5) { 1 }
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )
        val installer = RootfsInstaller(
            manager = manager,
            limits = RootfsInstallLimits(
                maxDownloadBytes = 5,
                maxExtractedBytes = 4,
                maxSingleEntryBytes = 4,
            ),
            connectionFactory = { TestHttpURLConnection(it, archive) },
        )

        val error = assertThrows(WorkspaceResourceLimitException::class.java) {
            installer.install("root", source)
        }

        assertTrue(error.message!!.contains("TEMP write requires"))
        assertFalse(manager.hasRootfs("root"))
    }

    @Test
    fun `install rejects staged rootfs that exceeds final rootfs quota`() {
        val archiveFile = tmp.newFile("healthy-rootfs.tar.gz")
        GZIPOutputStream(archiveFile.outputStream()).use { out ->
            out.writeTarEntry("etc/", '5', byteArrayOf())
            out.writeTarEntry("bin/sh", '0', byteArrayOf(1))
            out.writeTarEntry("bin/bash", '0', byteArrayOf(2))
            out.writeTarEntry("usr/bin/env", '0', byteArrayOf(3))
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val archive = archiveFile.readBytes()
        val manager = WorkspaceManager(
            baseDir = tmp.newFolder("rootfs-final-quota-workspaces"),
            config = WorkspaceConfig(
                resourceLimits = WorkspaceResourceLimits(
                    maxRootfsBytes = 1,
                    minFreeSpaceBytes = 0,
                )
            ),
        )
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )
        val installer = RootfsInstaller(
            manager = manager,
            connectionFactory = { TestHttpURLConnection(it, archive) },
        )

        val error = assertThrows(WorkspaceResourceLimitException::class.java) {
            installer.install("root", source)
        }

        assertTrue(error.message!!.contains("Installed Rootfs exceeds limit"))
        assertFalse(manager.hasRootfs("root"))
    }

    @Test
    fun `interrupted swap is recovered and failed install preserves previous Rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("rollback-workspaces"))
        val root = "root"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root)
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "usr/bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("old sh")
        File(linuxDir, "bin/bash").writeText("old bash")
        File(linuxDir, "usr/bin/env").writeText("old env")
        File(linuxDir, "old-marker").writeText("keep")
        val backupDir = File(manager.workspaceDir(root), ".linux-backup")
        assertTrue(linuxDir.renameTo(backupDir))
        assertFalse(linuxDir.exists())

        val archiveFile = tmp.newFile("invalid-rootfs.tar.gz")
        GZIPOutputStream(archiveFile.outputStream()).use { out ->
            out.writeTarEntry("README", '0', "invalid".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val archive = archiveFile.readBytes()
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )
        val installer = RootfsInstaller(
            manager = manager,
            connectionFactory = { TestHttpURLConnection(it, archive) },
        )

        assertThrows(IllegalArgumentException::class.java) {
            installer.install(root, source)
        }
        assertEquals("keep", File(linuxDir, "old-marker").readText())
        assertEquals("old sh", File(linuxDir, "bin/sh").readText())
    }

    @Test
    fun `successful replacement does not traverse symlinks in the previous rootfs`() {
        val manager = WorkspaceManager(tmp.newFolder("nofollow-replacement-workspaces"))
        val root = "root"
        manager.ensureWorkspace(root)
        val linuxDir = manager.linuxDir(root)
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "usr/bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("old sh")
        File(linuxDir, "bin/bash").writeText("old bash")
        File(linuxDir, "usr/bin/env").writeText("old env")
        val outside = tmp.newFolder("nofollow-replacement-outside")
        val sentinel = File(outside, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(linuxDir, "escape").toPath(), outside.toPath())

        val archiveFile = tmp.newFile("replacement-rootfs.tar.gz")
        GZIPOutputStream(archiveFile.outputStream()).use { out ->
            out.writeTarEntry("etc/", '5', byteArrayOf())
            out.writeTarEntry("bin/sh", '0', "new sh".toByteArray())
            out.writeTarEntry("bin/bash", '0', "new bash".toByteArray())
            out.writeTarEntry("usr/bin/env", '0', "new env".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val archive = archiveFile.readBytes()
        val source = RootfsArchiveSource(
            version = "test",
            androidAbi = "arm64-v8a",
            url = "https://example.test/rootfs.tar.gz",
            sha256 = archive.sha256(),
        )

        RootfsInstaller(
            manager = manager,
            connectionFactory = { TestHttpURLConnection(it, archive) },
        ).install(root, source)

        assertTrue(manager.hasRootfs(root))
        assertEquals("new sh", File(linuxDir, "bin/sh").readText())
        assertEquals("keep", sentinel.readText())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAR_BLOCK = 512
    }
}

private class TestHttpURLConnection(
    url: URL,
    private val bytes: ByteArray,
    private val beforeInput: () -> Unit = {},
) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = HTTP_OK
    override fun getContentLengthLong(): Long = bytes.size.toLong()
    override fun getInputStream(): ByteArrayInputStream {
        beforeInput()
        return ByteArrayInputStream(bytes)
    }
}
