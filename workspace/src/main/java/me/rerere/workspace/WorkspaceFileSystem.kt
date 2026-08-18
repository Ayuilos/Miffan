package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        return file.readText(charset)
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    /**
     * Writes without following any symbolic link in the target path.
     *
     * This is used for model-controlled Rootfs writes. PRoot bind mounts are userspace path
     * translations, so checking the host path here also prevents a guest path from being silently
     * redirected through a symlink before the write is opened.
     */
    fun writeTextNoFollow(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val segments = path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            RootfsHostFileBridge.writeFile(
                root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                path.toByteArray(StandardCharsets.UTF_8),
                bytes,
                false,
                overwrite,
            )
            return WorkspaceFileEntry(
                path = path,
                name = segments.last(),
                isDirectory = false,
                sizeBytes = bytes.size.toLong(),
                updatedAt = System.currentTimeMillis(),
            )
        }
        val file = resolveWritePathNoFollow(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        val options = mutableSetOf<OpenOption>(
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (overwrite) {
            options += StandardOpenOption.CREATE
            options += StandardOpenOption.TRUNCATE_EXISTING
        } else {
            options += StandardOpenOption.CREATE_NEW
        }
        Files.newByteChannel(file.toPath(), options).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        return file.toEntry(root)
    }

    fun fileSizeNoFollow(root: File, path: String, displayPath: String = path): Long {
        path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            return when (
                val size = RootfsHostFileBridge.fileSize(
                    root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                    path.toByteArray(StandardCharsets.UTF_8),
                    false,
                )
            ) {
                -1L -> throw IllegalArgumentException("File does not exist: $displayPath")
                -2L -> throw IllegalArgumentException("Path is not a file: $displayPath")
                else -> size
            }
        }
        val file = resolveReadPathNoFollow(root, path, displayPath)
        return Files.size(file.toPath())
    }

    fun exportNoFollow(
        root: File,
        path: String,
        outputStream: OutputStream,
        maxBytes: Long,
        displayPath: String = path,
    ) {
        require(maxBytes in 0..MAX_DESCRIPTOR_FILE_BYTES) {
            "Invalid descriptor-relative read limit: $maxBytes"
        }
        path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            val bytes = requireNotNull(
                RootfsHostFileBridge.readFile(
                    root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                    path.toByteArray(StandardCharsets.UTF_8),
                    maxBytes.toInt(),
                    false,
                )
            ) { "File does not exist: $displayPath" }
            outputStream.write(bytes)
            return
        }

        val file = resolveReadPathNoFollow(root, path, displayPath)
        val output = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        Files.newByteChannel(
            file.toPath(),
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(output.size().toLong() + read <= maxBytes) {
                    "File is too large to read: $displayPath"
                }
                output.write(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        output.writeTo(outputStream)
    }

    fun importBytes(
        root: File,
        path: String,
        inputStream: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
    ): WorkspaceFileEntry {
        require(maxBytes >= 0) { "Import capacity must not be negative" }
        val file = resolvePath(root, path)
        file.parentFile?.mkdirs()
        val target = if (!file.exists()) file else resolveConflict(file)
        return try {
            inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written = Math.addExact(written, read.toLong())
                        if (written > maxBytes) {
                            throw WorkspaceResourceLimitException(
                                "Import exceeds remaining workspace capacity: $maxBytes bytes"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            target.toEntry(root)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun resolveConflict(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do { candidate = File(file.parentFile, "$stem ($n)$ext"); n++ } while (candidate.exists())
        return candidate
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolvePath(root, path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursivelyNoFollow()
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursivelyNoFollow()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    private fun resolveWritePathNoFollow(root: File, path: String): File {
        val segments = path.strictRelativeFileSegments()

        root.mkdirs()
        require(!Files.isSymbolicLink(root.toPath())) { "Workspace root must not be a symbolic link" }
        val rootFile = root.canonicalFile
        var current = rootFile
        segments.dropLast(1).forEach { segment ->
            val next = File(current, segment)
            require(!Files.isSymbolicLink(next.toPath())) {
                "Refusing to write through symbolic link: ${next.relativeTo(rootFile).path}"
            }
            if (next.exists()) {
                require(next.isDirectory) { "Path component is not a directory: $segment" }
            } else {
                require(next.mkdir()) { "Failed to create directory: $segment" }
            }
            current = next
        }

        val target = File(current, segments.last())
        require(!Files.isSymbolicLink(target.toPath())) {
            "Refusing to write through symbolic link: $path"
        }
        val targetCanonical = target.canonicalFile
        require(
            targetCanonical.path == rootFile.path ||
                targetCanonical.path.startsWith(rootFile.path + File.separator)
        ) { "Path escapes workspace root: $path" }
        return target
    }

    private fun resolveReadPathNoFollow(root: File, path: String, displayPath: String): File {
        val segments = path.strictRelativeFileSegments()
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace root must be a real directory"
        }
        var current = root.toPath().toAbsolutePath().normalize()
        segments.dropLast(1).forEach { segment ->
            current = current.resolve(segment)
            require(Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                "File does not exist: $displayPath"
            }
            require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "Refusing to read through symbolic link or non-directory: $displayPath"
            }
        }
        val target = current.resolve(segments.last())
        require(Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "File does not exist: $displayPath"
        }
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "Path is not a file: $displayPath"
        }
        return target.toFile()
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }

    private fun String.strictRelativeFileSegments(): List<String> {
        require(isNotBlank() && this != ".") { "Path must identify a file" }
        require(!startsWith('/') && !contains('\\') && !contains('\u0000')) {
            "Path must be an unambiguous relative path"
        }
        return split('/').also { segments ->
            require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                "Path must not contain empty, . or .. segments"
            }
            require(segments.all { it.toByteArray(StandardCharsets.UTF_8).size <= 255 }) {
                "Path segment is too long"
            }
        }
    }

    private companion object {
        const val MAX_DESCRIPTOR_FILE_BYTES = 8L * 1024 * 1024
    }
}
