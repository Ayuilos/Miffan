package me.rerere.workspace

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission

internal interface RootfsExtractionFiles {
    fun directory(relativePath: String)
    fun openFile(relativePath: String, mode: Int): OutputStream
    fun symlink(relativePath: String, linkTarget: String)
    /** Returns the linked file's logical size for extraction quota accounting. */
    fun hardLink(relativePath: String, sourceRelativePath: String): Long
    fun setModifiedAt(relativePath: String, modifiedAtMillis: Long)
}

internal fun rootfsExtractionFiles(root: File): RootfsExtractionFiles =
    if (usesNativeHostFileOperations()) {
        NativeRootfsExtractionFiles(root.absolutePath.toByteArray(StandardCharsets.UTF_8))
    } else {
        NioRootfsExtractionFiles(root.toPath().toAbsolutePath().normalize())
    }

private class NativeRootfsExtractionFiles(
    private val root: ByteArray,
) : RootfsExtractionFiles {
    override fun directory(relativePath: String) {
        check(RootfsHostFileBridge.directory(root, relativePath.encodedArchivePath(), true)) {
            "Unable to create Rootfs archive directory: /$relativePath"
        }
    }

    override fun openFile(relativePath: String, mode: Int): OutputStream {
        val descriptor = RootfsHostFileBridge.openArchiveFile(
            root,
            relativePath.encodedArchivePath(),
            mode and ARCHIVE_MODE_MASK,
        )
        check(descriptor >= 0) { "Unable to create Rootfs archive file: /$relativePath" }
        return ParcelFileDescriptor.AutoCloseOutputStream(
            ParcelFileDescriptor.adoptFd(descriptor)
        )
    }

    override fun symlink(relativePath: String, linkTarget: String) {
        require(linkTarget.isNotEmpty() && !linkTarget.contains('\u0000')) {
            "Rootfs archive symlink target is invalid"
        }
        RootfsHostFileBridge.createArchiveSymlink(
            root,
            relativePath.encodedArchivePath(),
            linkTarget.toByteArray(StandardCharsets.UTF_8),
        )
    }

    override fun hardLink(relativePath: String, sourceRelativePath: String): Long {
        require(relativePath != sourceRelativePath) { "Rootfs archive hard link targets itself" }
        return RootfsHostFileBridge.createArchiveHardLink(
            root,
            sourceRelativePath.encodedArchivePath(),
            relativePath.encodedArchivePath(),
        ).also { check(it >= 0) { "Unable to create Rootfs archive hard link" } }
    }

    override fun setModifiedAt(relativePath: String, modifiedAtMillis: Long) {
        RootfsHostFileBridge.setEntryMtime(
            root,
            relativePath.encodedArchivePath(),
            modifiedAtMillis,
        )
    }
}

/** JVM test backend mirroring the Android descriptor-relative extraction rules. */
private class NioRootfsExtractionFiles(
    private val root: Path,
) : RootfsExtractionFiles {
    init {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs extraction root must be a real directory"
        }
    }

    override fun directory(relativePath: String) {
        resolveDirectory(relativePath.archiveSegments(), create = true)
    }

    override fun openFile(relativePath: String, mode: Int): OutputStream {
        val target = resolveLeaf(relativePath, createParent = true)
        removeReplaceableLeaf(target, relativePath)
        val channel = Files.newByteChannel(
            target,
            setOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS,
            ),
        )
        val output = Channels.newOutputStream(channel)
        return object : OutputStream() {
            override fun write(value: Int) = output.write(value)

            override fun write(buffer: ByteArray, offset: Int, length: Int) =
                output.write(buffer, offset, length)

            override fun flush() = output.flush()

            override fun close() {
                output.close()
                target.applyArchiveMode(mode)
            }
        }
    }

    override fun symlink(relativePath: String, linkTarget: String) {
        require(linkTarget.isNotEmpty() && !linkTarget.contains('\u0000')) {
            "Rootfs archive symlink target is invalid"
        }
        val target = resolveLeaf(relativePath, createParent = true)
        removeReplaceableLeaf(target, relativePath)
        Files.createSymbolicLink(target, target.fileSystem.getPath(linkTarget))
    }

    override fun hardLink(relativePath: String, sourceRelativePath: String): Long {
        require(relativePath != sourceRelativePath) { "Rootfs archive hard link targets itself" }
        val source = resolveLeaf(sourceRelativePath, createParent = false)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs archive hard-link source is not a regular file: /$sourceRelativePath"
        }
        val sourceSize = Files.newByteChannel(
            source,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { it.size() }
        val target = resolveLeaf(relativePath, createParent = true)
        removeReplaceableLeaf(target, relativePath)
        try {
            Files.createLink(target, source)
        } catch (error: Throwable) {
            if (error !is java.io.IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            copyRegularFile(source, target)
        }
        return sourceSize
    }

    override fun setModifiedAt(relativePath: String, modifiedAtMillis: Long) {
        val target = resolveLeaf(relativePath, createParent = false)
        require(
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
        ) { "Unsafe Rootfs archive mtime target: /$relativePath" }
        Files.setLastModifiedTime(target, FileTime.fromMillis(modifiedAtMillis))
    }

    private fun copyRegularFile(source: Path, target: Path) {
        Files.newByteChannel(
            source,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { input ->
            Files.newByteChannel(
                target,
                setOf<OpenOption>(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ).use { output ->
                val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                }
            }
        }
        target.applyArchiveModeFrom(source)
    }

    private fun resolveLeaf(relativePath: String, createParent: Boolean): Path {
        val segments = relativePath.archiveSegments()
        val parent = resolveDirectory(segments.dropLast(1), createParent)
        return parent.resolve(segments.last())
    }

    private fun resolveDirectory(segments: List<String>, create: Boolean): Path {
        var current = root
        segments.forEach { segment ->
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(create) { "Rootfs archive directory is missing: $segment" }
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // Re-check the object that won the race below.
                }
            }
            require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "Refusing Rootfs archive extraction through symbolic link or non-directory"
            }
        }
        return current
    }

    private fun removeReplaceableLeaf(target: Path, relativePath: String) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs archive entry would replace a directory: /$relativePath"
        }
        Files.delete(target)
    }
}

private fun String.encodedArchivePath(): ByteArray {
    archiveSegments()
    return toByteArray(StandardCharsets.UTF_8)
}

private fun String.archiveSegments(): List<String> {
    require(isNotBlank() && !startsWith('/') && !contains('\\') && !contains('\u0000')) {
        "Rootfs archive path must be an unambiguous relative path"
    }
    return split('/').also { segments ->
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Rootfs archive path is ambiguous"
        }
        require(segments.all { it.toByteArray(StandardCharsets.UTF_8).size <= 255 }) {
            "Rootfs archive path segment is too long"
        }
    }
}

private fun Path.applyArchiveMode(mode: Int) {
    Files.setPosixFilePermissions(this, (mode and 0b111_111_111).toPosixPermissions())
}

private fun Path.applyArchiveModeFrom(source: Path) {
    Files.setPosixFilePermissions(
        this,
        Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS),
    )
}

private fun Int.toPosixPermissions(): Set<PosixFilePermission> = buildSet {
    if (this@toPosixPermissions and 0b100_000_000 != 0) add(PosixFilePermission.OWNER_READ)
    if (this@toPosixPermissions and 0b010_000_000 != 0) add(PosixFilePermission.OWNER_WRITE)
    if (this@toPosixPermissions and 0b001_000_000 != 0) add(PosixFilePermission.OWNER_EXECUTE)
    if (this@toPosixPermissions and 0b000_100_000 != 0) add(PosixFilePermission.GROUP_READ)
    if (this@toPosixPermissions and 0b000_010_000 != 0) add(PosixFilePermission.GROUP_WRITE)
    if (this@toPosixPermissions and 0b000_001_000 != 0) add(PosixFilePermission.GROUP_EXECUTE)
    if (this@toPosixPermissions and 0b000_000_100 != 0) add(PosixFilePermission.OTHERS_READ)
    if (this@toPosixPermissions and 0b000_000_010 != 0) add(PosixFilePermission.OTHERS_WRITE)
    if (this@toPosixPermissions and 0b000_000_001 != 0) add(PosixFilePermission.OTHERS_EXECUTE)
}

private const val ARCHIVE_MODE_MASK = 0xFFF
