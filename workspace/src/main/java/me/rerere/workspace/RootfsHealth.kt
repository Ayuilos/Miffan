package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

object RootfsHealth {
    fun isHealthy(rootfs: File): Boolean = try {
        requireHealthy(rootfs)
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun requireHealthy(rootfs: File) {
        require(Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs health check failed: root is not a real directory"
        }
        val root = rootfs.canonicalFile
        REQUIRED_REAL_DIRECTORIES.forEach { relativePath ->
            require(Files.isDirectory(File(root, relativePath).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Rootfs health check failed: /$relativePath is not a real directory"
            }
        }
        REQUIRED_FILES.forEach { relativePath ->
            val target = File(root, relativePath).canonicalFile
            require(
                target.isWithin(root) &&
                    Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                "Rootfs health check failed: missing /$relativePath"
            }
        }
    }

    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path + File.separator)

    private val REQUIRED_REAL_DIRECTORIES = listOf("etc")
    private val REQUIRED_FILES = listOf("bin/sh", "bin/bash", "usr/bin/env")
}
