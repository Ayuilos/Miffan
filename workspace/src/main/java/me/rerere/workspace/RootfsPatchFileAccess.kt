package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

internal enum class RootfsPatchFileKind {
    REGULAR,
    SYMLINK,
}

internal fun interface RootfsPatchFilesFactory {
    fun open(root: File): RootfsPatchFiles?
}

internal interface RootfsPatchFiles {
    fun directory(relativePath: String, create: Boolean): Boolean
    fun fileKind(relativePath: String, allowLeafSymlink: Boolean = false): RootfsPatchFileKind?
    fun readTextIfExists(relativePath: String): String?
    fun readText(relativePath: String): String
    fun writeText(relativePath: String, text: String, replaceLeafSymlink: Boolean = false)
    fun chmodDirectory(relativePath: String, mode: Int)
}

internal object NativeRootfsPatchFilesFactory : RootfsPatchFilesFactory {
    override fun open(root: File): RootfsPatchFiles? {
        val rootBytes = root.absolutePath.toByteArray(StandardCharsets.UTF_8)
        return if (RootfsHostFileBridge.directory(rootBytes, EMPTY_PATH, false)) {
            NativeRootfsPatchFiles(rootBytes)
        } else {
            null
        }
    }

    private val EMPTY_PATH = ByteArray(0)
}

private class NativeRootfsPatchFiles(
    private val root: ByteArray,
) : RootfsPatchFiles {
    override fun directory(relativePath: String, create: Boolean): Boolean =
        RootfsHostFileBridge.directory(root, relativePath.encodedPath(allowEmpty = true), create)

    override fun fileKind(
        relativePath: String,
        allowLeafSymlink: Boolean,
    ): RootfsPatchFileKind? = when (
        val kind = RootfsHostFileBridge.fileKind(root, relativePath.encodedPath())
    ) {
        RootfsHostFileBridge.FILE_MISSING -> null
        RootfsHostFileBridge.FILE_REGULAR -> RootfsPatchFileKind.REGULAR
        RootfsHostFileBridge.FILE_SYMLINK -> {
            require(allowLeafSymlink) {
                "Refusing Rootfs host access through symbolic link: /$relativePath"
            }
            RootfsPatchFileKind.SYMLINK
        }
        else -> error("Unexpected native Rootfs file kind: $kind")
    }

    override fun readTextIfExists(relativePath: String): String? =
        RootfsHostFileBridge.readFile(
            root,
            relativePath.encodedPath(),
            MAX_MAINTENANCE_FILE_BYTES,
            true,
        )?.toString(StandardCharsets.UTF_8)

    override fun readText(relativePath: String): String =
        requireNotNull(readTextIfExists(relativePath)) {
            "Rootfs maintenance file is missing: /$relativePath"
        }

    override fun writeText(relativePath: String, text: String, replaceLeafSymlink: Boolean) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_MAINTENANCE_FILE_BYTES) {
            "Rootfs maintenance file is too large: /$relativePath"
        }
        RootfsHostFileBridge.writeFile(
            root,
            relativePath.encodedPath(),
            bytes,
            replaceLeafSymlink,
            true,
        )
    }

    override fun chmodDirectory(relativePath: String, mode: Int) {
        RootfsHostFileBridge.chmodDirectory(root, relativePath.encodedPath(allowEmpty = true), mode)
    }
}

/** JVM-only test backend; Android production always uses [NativeRootfsPatchFilesFactory]. */
internal object NioRootfsPatchFilesFactory : RootfsPatchFilesFactory {
    override fun open(root: File): RootfsPatchFiles? {
        val path = root.toPath().toAbsolutePath().normalize()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs directory must not be a symbolic link"
        }
        return NioRootfsPatchFiles(path)
    }
}

private class NioRootfsPatchFiles(
    private val root: Path,
) : RootfsPatchFiles {
    override fun directory(relativePath: String, create: Boolean): Boolean {
        var current = root
        for (segment in relativePath.segments(allowEmpty = true)) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return false
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // Re-check below: tests must reject the same unsafe shape as production.
                }
            }
            require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "Refusing Rootfs host access through symbolic link or non-directory: /$relativePath"
            }
        }
        return true
    }

    override fun fileKind(
        relativePath: String,
        allowLeafSymlink: Boolean,
    ): RootfsPatchFileKind? {
        val target = resolveFile(relativePath) ?: return null
        if (Files.isSymbolicLink(target)) {
            require(allowLeafSymlink) {
                "Refusing Rootfs host access through symbolic link: /$relativePath"
            }
            return RootfsPatchFileKind.SYMLINK
        }
        requireSafeRegularFile(target, relativePath)
        return RootfsPatchFileKind.REGULAR
    }

    override fun readTextIfExists(relativePath: String): String? {
        val target = resolveFile(relativePath) ?: return null
        requireSafeRegularFile(target, relativePath)
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        Files.newByteChannel(
            target,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(output.size().toLong() + read <= MAX_MAINTENANCE_FILE_BYTES) {
                    "Rootfs maintenance file is too large: /$relativePath"
                }
                output.write(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    override fun readText(relativePath: String): String =
        requireNotNull(readTextIfExists(relativePath)) {
            "Rootfs maintenance file is missing: /$relativePath"
        }

    override fun writeText(relativePath: String, text: String, replaceLeafSymlink: Boolean) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_MAINTENANCE_FILE_BYTES) {
            "Rootfs maintenance file is too large: /$relativePath"
        }
        val segments = relativePath.segments()
        val parentPath = segments.dropLast(1).joinToString("/")
        check(directory(parentPath, create = true))
        val target = root.resolve(segments.joinToString("/"))
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) {
                require(replaceLeafSymlink) {
                    "Refusing Rootfs host access through symbolic link: /$relativePath"
                }
                Files.delete(target)
            } else {
                requireSafeRegularFile(target, relativePath)
            }
        }
        Files.newByteChannel(
            target,
            setOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    override fun chmodDirectory(relativePath: String, mode: Int) {
        var target = root
        relativePath.segments(allowEmpty = true).forEach { target = target.resolve(it) }
        require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing Rootfs chmod through symbolic link or non-directory: /$relativePath"
        }
        Files.setPosixFilePermissions(target, mode.toPosixPermissions())
    }

    private fun resolveFile(relativePath: String): Path? {
        val segments = relativePath.segments()
        val parentPath = segments.dropLast(1).joinToString("/")
        if (!directory(parentPath, create = false)) return null
        val target = root.resolve(segments.joinToString("/"))
        return target.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun requireSafeRegularFile(target: Path, relativePath: String) {
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs maintenance path is not a regular file: /$relativePath"
        }
        val linkCount = runCatching {
            (Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull()
        require(linkCount == null || linkCount == 1L) {
            "Refusing Rootfs host access through hard-linked file: /$relativePath"
        }
    }
}

private fun String.encodedPath(allowEmpty: Boolean = false): ByteArray {
    segments(allowEmpty)
    return toByteArray(StandardCharsets.UTF_8)
}

private fun String.segments(allowEmpty: Boolean = false): List<String> {
    if (isEmpty() && allowEmpty) return emptyList()
    require(isNotBlank() && !startsWith('/') && !contains('\\') && !contains('\u0000')) {
        "Rootfs maintenance path must be an unambiguous relative path"
    }
    return split('/').also { segments ->
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Rootfs maintenance path is ambiguous"
        }
        require(segments.all { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_SEGMENT_BYTES }) {
            "Rootfs maintenance path segment is too long"
        }
    }
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

private const val MAX_MAINTENANCE_FILE_BYTES = 256 * 1024
private const val MAX_SEGMENT_BYTES = 255
