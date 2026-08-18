package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes a tree without traversing symbolic links or reusing validated host pathnames. */
internal fun File.deleteRecursivelyNoFollow(): Boolean =
    DefaultHostFileOperations.deleteTree(this)

/** Resolves and deletes a strict relative tree below this directory. */
internal fun File.deleteRelativeTreeNoFollow(relativePath: String): Boolean {
    relativePath.requireStrictRelativePath()
    return DefaultHostFileOperations.deleteRelativeTree(this, relativePath)
}

/** Creates a strict relative directory tree below this directory. */
internal fun File.ensureDirectoryNoFollow(relativePath: String): File {
    relativePath.requireStrictRelativePath()
    return DefaultHostFileOperations.ensureDirectory(this, relativePath)
}

/** Atomically moves a real directory without replacing an existing destination entry. */
internal fun File.renameDirectoryNoFollow(target: File): Boolean =
    DefaultHostFileOperations.renameDirectoryNoReplace(this, target)

internal fun usesNativeHostFileOperations(): Boolean =
    DefaultHostFileOperations === NativeHostFileOperations

private interface HostFileOperations {
    fun deleteTree(target: File): Boolean
    fun deleteRelativeTree(root: File, relativePath: String): Boolean
    fun ensureDirectory(root: File, relativePath: String): File
    fun renameDirectoryNoReplace(source: File, target: File): Boolean
}

private val DefaultHostFileOperations: HostFileOperations =
    if (isAndroidRuntime()) NativeHostFileOperations else NioHostFileOperations

private object NativeHostFileOperations : HostFileOperations {
    override fun deleteTree(target: File): Boolean =
        RootfsHostFileBridge.deleteTree(target.encodedAbsolutePath())

    override fun deleteRelativeTree(root: File, relativePath: String): Boolean =
        deleteTree(File(root, relativePath))

    override fun ensureDirectory(root: File, relativePath: String): File {
        check(
            RootfsHostFileBridge.directory(
                root.encodedAbsolutePath(),
                relativePath.toByteArray(StandardCharsets.UTF_8),
                true,
            )
        ) { "Unable to create host maintenance directory: $relativePath" }
        return File(root, relativePath)
    }

    override fun renameDirectoryNoReplace(source: File, target: File): Boolean =
        RootfsHostFileBridge.renameDirectoryNoReplace(
            source.encodedAbsolutePath(),
            target.encodedAbsolutePath(),
        )
}

/** JVM test backend. Android selects the native descriptor-relative implementation above. */
private object NioHostFileOperations : HostFileOperations {
    override fun deleteTree(target: File): Boolean {
        val root = target.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw error
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        check(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            "Host maintenance path was recreated during deletion: $target"
        }
        return true
    }

    override fun deleteRelativeTree(root: File, relativePath: String): Boolean {
        val target = resolveRelative(root, relativePath, createDirectories = false) ?: return true
        return deleteTree(target)
    }

    override fun ensureDirectory(root: File, relativePath: String): File =
        requireNotNull(resolveRelative(root, relativePath, createDirectories = true))

    private fun resolveRelative(
        root: File,
        relativePath: String,
        createDirectories: Boolean,
    ): File? {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            "Host maintenance root must be a real directory: $root"
        }
        var current = rootPath
        val segments = relativePath.split('/')
        segments.forEachIndexed { index, segment ->
            current = current.resolve(segment)
            val isLeaf = index == segments.lastIndex
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!createDirectories) return null
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // Re-check below: tests reject the same unsafe shape as production.
                }
            }
            if (!isLeaf || createDirectories) {
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Refusing host maintenance through symbolic link or non-directory: $relativePath"
                }
            }
        }
        return current.toFile()
    }

    override fun renameDirectoryNoReplace(source: File, target: File): Boolean {
        val sourcePath = source.toPath()
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) return false
        require(Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            "Host maintenance source must be a real directory: $source"
        }
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Host maintenance destination already exists: $target"
        }
        val targetParent = requireNotNull(target.parentFile).toPath()
        require(Files.isDirectory(targetParent, LinkOption.NOFOLLOW_LINKS)) {
            "Host maintenance destination parent must be a real directory: $targetParent"
        }
        Files.move(sourcePath, target.toPath())
        return true
    }
}

private fun File.encodedAbsolutePath(): ByteArray =
    absolutePath.toByteArray(StandardCharsets.UTF_8)

private fun String.requireStrictRelativePath() {
    require(
        isNotBlank() &&
            !startsWith('/') &&
            !contains('\\') &&
            !contains('\u0000')
    ) { "Path must be an unambiguous relative path" }
    val segments = split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Path must not contain empty, . or .. segments"
    }
    require(segments.all { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_SEGMENT_BYTES }) {
        "Path segment is too long"
    }
}

private fun isAndroidRuntime(): Boolean =
    System.getProperty("java.runtime.name") == "Android Runtime" ||
        System.getProperty("java.vendor") == "The Android Project" ||
        System.getProperty("java.vm.name") == "Dalvik"

private const val MAX_SEGMENT_BYTES = 255
