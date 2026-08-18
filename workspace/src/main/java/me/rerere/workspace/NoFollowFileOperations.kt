package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes a tree without ever traversing a symbolic link. */
internal fun File.deleteRecursivelyNoFollow(): Boolean {
    val root = toPath()
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

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
    check(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        "Host maintenance path was recreated during deletion: $this"
    }
    return true
}

/** Resolves and deletes a relative tree while rejecting symlinks in every parent component. */
internal fun File.deleteRelativeTreeNoFollow(relativePath: String): Boolean {
    val target = resolveRelativeNoFollow(relativePath, createDirectories = false) ?: return true
    return target.deleteRecursivelyNoFollow()
}

/** Creates a relative directory tree while rejecting symlinks in every component. */
internal fun File.ensureDirectoryNoFollow(relativePath: String): File =
    requireNotNull(resolveRelativeNoFollow(relativePath, createDirectories = true))

private fun File.resolveRelativeNoFollow(
    relativePath: String,
    createDirectories: Boolean,
): File? {
    require(relativePath.isNotBlank() && !relativePath.startsWith('/') && !relativePath.contains('\\')) {
        "Path must be an unambiguous relative path"
    }
    val segments = relativePath.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Path must not contain empty, . or .. segments"
    }

    val root = toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        "Host maintenance root must be a real directory: $this"
    }
    var current = root
    segments.forEachIndexed { index, segment ->
        current = current.resolve(segment)
        val isLeaf = index == segments.lastIndex
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            if (!createDirectories) return null
            try {
                Files.createDirectory(current)
            } catch (_: FileAlreadyExistsException) {
                // Re-check below: a concurrent symlink replacement must not be accepted.
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
