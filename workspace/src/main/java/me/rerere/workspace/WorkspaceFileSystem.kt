package me.rerere.workspace

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    init {
        require(config.maxReadBytes in 0..MAX_DESCRIPTOR_FILE_BYTES) {
            "Workspace read limit exceeds descriptor bridge capacity"
        }
        require(config.maxWriteBytes in 0..MAX_DESCRIPTOR_FILE_BYTES) {
            "Workspace write limit exceeds descriptor bridge capacity"
        }
        require(config.maxListEntries in 1..MAX_DISCOVERY_SCAN_ENTRIES) {
            "Workspace list limit is outside the discovery capacity"
        }
        require(config.maxSearchResults in 1..MAX_DISCOVERY_SCAN_ENTRIES) {
            "Workspace search limit is outside the discovery capacity"
        }
    }

    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        if (usesNativeHostFileOperations()) {
            return discoverNative(root, path, recursive = false)
                .sortedWith(
                    compareBy<WorkspaceFileEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .take(config.maxListEntries)
        }
        val dir = resolveExistingPathNoFollow(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .sortedWith(
                compareBy<File> {
                    !Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
                }.thenBy { it.name.lowercase() }
            )
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val size = fileSizeNoFollow(root, path)
        require(size <= config.maxReadBytes) { "File is too large to read: $size bytes" }
        val output = ByteArrayOutputStream(size.toInt())
        exportNoFollow(root, path, output, config.maxReadBytes)
        return output.toString(charset.name())
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        return writeTextNoFollow(root, path, text, overwrite, charset)
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

    fun existingFileSizeNoFollow(
        root: File,
        path: String,
        displayPath: String = path,
    ): Long? {
        val segments = path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            return when (
                val size = RootfsHostFileBridge.fileSize(
                    root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                    path.toByteArray(StandardCharsets.UTF_8),
                    false,
                )
            ) {
                -1L -> null
                -2L -> throw IllegalArgumentException("Path is not a file: $displayPath")
                else -> size
            }
        }
        val rootPath = root.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace root must be a real directory"
        }
        var parent = rootPath
        segments.dropLast(1).forEach { segment ->
            parent = parent.resolve(segment)
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return null
            require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                "Refusing to inspect through symbolic link or non-directory: $displayPath"
            }
        }
        val target = parent.resolve(segments.last())
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            "Path is not a file: $displayPath"
        }
        Files.newByteChannel(
            target,
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { return it.size() }
    }

    fun exportNoFollow(
        root: File,
        path: String,
        outputStream: OutputStream,
        maxBytes: Long,
        displayPath: String = path,
    ) {
        require(maxBytes >= 0) { "Invalid descriptor-relative read limit: $maxBytes" }
        path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            val descriptor = RootfsHostFileBridge.openFileRead(
                root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                path.toByteArray(StandardCharsets.UTF_8),
                false,
            )
            require(descriptor != -1) { "File does not exist: $displayPath" }
            require(descriptor != -2) { "Path is not a file: $displayPath" }
            check(descriptor >= 0) { "Unable to open file: $displayPath" }
            val parcelDescriptor = ParcelFileDescriptor.adoptFd(descriptor)
            try {
                val initialSize = parcelDescriptor.statSize
                require(initialSize < 0 || initialSize <= maxBytes) {
                    "File is too large to read: $displayPath"
                }
                ParcelFileDescriptor.AutoCloseInputStream(parcelDescriptor).use { input ->
                    input.copyBoundedTo(outputStream, maxBytes, displayPath)
                }
            } catch (error: Throwable) {
                runCatching { parcelDescriptor.close() }
                throw error
            }
            return
        }

        val file = resolveReadPathNoFollow(root, path, displayPath)
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        Files.newByteChannel(
            file.toPath(),
            setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            require(channel.size() <= maxBytes) { "File is too large to read: $displayPath" }
            var copied = 0L
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied = Math.addExact(copied, read.toLong())
                require(copied <= maxBytes) {
                    "File is too large to read: $displayPath"
                }
                outputStream.write(buffer.array(), 0, read)
                buffer.clear()
            }
        }
    }

    private fun InputStream.copyBoundedTo(
        outputStream: OutputStream,
        maxBytes: Long,
        displayPath: String,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) return
            if (count == 0) continue
            copied = Math.addExact(copied, count.toLong())
            require(copied <= maxBytes) { "File is too large to read: $displayPath" }
            outputStream.write(buffer, 0, count)
        }
    }

    fun importBytes(
        root: File,
        path: String,
        inputStream: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
    ): WorkspaceFileEntry {
        require(maxBytes >= 0) { "Import capacity must not be negative" }
        val segments = path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            return importBytesNative(root, path, segments, inputStream, maxBytes)
        }
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

    /** Writes one snapshot entry at its exact path without following links or resolving conflicts. */
    internal fun importExact(
        root: File,
        path: String,
        size: Long,
        inputStream: InputStream,
    ) {
        require(size in 0..config.resourceLimits.maxShellFileBytes) {
            "Workspace snapshot file exceeds per-file limit: $path ($size bytes)"
        }
        path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            importExactNative(root, path, size, inputStream)
            return
        }
        val target = resolveWritePathNoFollow(root, path)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Duplicate workspace snapshot entry: $path"
        }
        val options = setOf<OpenOption>(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE_NEW,
            LinkOption.NOFOLLOW_LINKS,
        )
        try {
            Files.newOutputStream(target.toPath(), *options.toTypedArray()).use { output ->
                inputStream.copyExactly(output, size, path)
            }
        } catch (error: Throwable) {
            Files.deleteIfExists(target.toPath())
            throw error
        }
    }

    private fun importExactNative(root: File, path: String, size: Long, inputStream: InputStream) {
        val rootBytes = root.absolutePath.toByteArray(StandardCharsets.UTF_8)
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val descriptor = RootfsHostFileBridge.openFileCreate(rootBytes, pathBytes)
        require(descriptor >= 0) { "Duplicate or unsafe workspace snapshot entry: $path" }
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor)
            ).use { output ->
                inputStream.copyExactly(output, size, path)
            }
        } catch (error: Throwable) {
            runCatching { RootfsHostFileBridge.deleteRelative(rootBytes, pathBytes, false) }
            throw error
        }
    }

    private fun InputStream.copyExactly(output: OutputStream, size: Long, path: String) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Workspace snapshot transfer cancelled")
            }
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Workspace snapshot ended inside file: $path")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun importBytesNative(
        root: File,
        path: String,
        segments: List<String>,
        inputStream: InputStream,
        maxBytes: Long,
    ): WorkspaceFileEntry {
        val rootBytes = root.absolutePath.toByteArray(StandardCharsets.UTF_8)
        var attempt = 0
        var targetPath: String
        var descriptor: Int
        while (true) {
            targetPath = conflictPath(path, segments, attempt)
            descriptor = RootfsHostFileBridge.openFileCreate(
                rootBytes,
                targetPath.toByteArray(StandardCharsets.UTF_8),
            )
            if (descriptor >= 0) break
            check(descriptor == -2) { "Unable to create imported workspace file" }
            attempt++
            require(attempt <= MAX_IMPORT_CONFLICTS) { "Too many conflicting import file names" }
        }

        var written = 0L
        try {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor)
            )
            inputStream.use { input ->
                output.use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written = Math.addExact(written, read.toLong())
                        if (written > maxBytes) {
                            throw WorkspaceResourceLimitException(
                                "Import exceeds remaining workspace capacity: $maxBytes bytes"
                            )
                        }
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching {
                RootfsHostFileBridge.deleteRelative(
                    rootBytes,
                    targetPath.toByteArray(StandardCharsets.UTF_8),
                    true,
                )
            }
            throw error
        }
        return WorkspaceFileEntry(
            path = targetPath,
            name = targetPath.substringAfterLast('/'),
            isDirectory = false,
            sizeBytes = written,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun conflictPath(path: String, segments: List<String>, attempt: Int): String {
        if (attempt == 0) return path
        val name = File(segments.last())
        val extension = name.extension.let { if (it.isEmpty()) "" else ".$it" }
        val candidateName = "${name.nameWithoutExtension} ($attempt)$extension"
        return (segments.dropLast(1) + candidateName).joinToString("/")
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
        path.strictRelativeFileSegments()
        if (usesNativeHostFileOperations()) {
            return RootfsHostFileBridge.deleteRelative(
                root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                path.toByteArray(StandardCharsets.UTF_8),
                recursive,
            )
        }
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
        val sourceSegments = source.strictRelativeFileSegments()
        val targetSegments = target.strictRelativeFileSegments()
        require(sourceSegments != targetSegments) { "Source and target must differ" }
        require(
            !sourceSegments.isPrefixOf(targetSegments) && !targetSegments.isPrefixOf(sourceSegments)
        ) { "Source and target must not contain one another" }
        if (usesNativeHostFileOperations()) {
            return moveNative(root, source, target, targetSegments, overwrite)
        }
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

    private fun moveNative(
        root: File,
        source: String,
        target: String,
        targetSegments: List<String>,
        overwrite: Boolean,
    ): WorkspaceFileEntry {
        val rootBytes = root.absolutePath.toByteArray(StandardCharsets.UTF_8)
        val sourceBytes = source.toByteArray(StandardCharsets.UTF_8)
        val targetBytes = target.toByteArray(StandardCharsets.UTF_8)
        val kind = RootfsHostFileBridge.entryKind(rootBytes, sourceBytes)
        require(kind != RootfsHostFileBridge.ENTRY_MISSING) { "Source does not exist: $source" }
        require(
            kind == RootfsHostFileBridge.ENTRY_REGULAR ||
                kind == RootfsHostFileBridge.ENTRY_DIRECTORY
        ) { "Source must be a real regular file or directory: $source" }

        val targetParent = targetSegments.dropLast(1).joinToString("/")
        if (targetParent.isNotEmpty()) root.ensureDirectoryNoFollow(targetParent)
        if (overwrite) {
            RootfsHostFileBridge.deleteRelative(rootBytes, targetBytes, true)
        }
        check(RootfsHostFileBridge.renameEntryNoReplace(rootBytes, sourceBytes, targetBytes)) {
            "Failed to move $source to $target"
        }
        val isDirectory = kind == RootfsHostFileBridge.ENTRY_DIRECTORY
        val size = if (isDirectory) {
            0L
        } else {
            RootfsHostFileBridge.fileSize(rootBytes, targetBytes, false)
        }
        return WorkspaceFileEntry(
            path = target,
            name = targetSegments.last(),
            isDirectory = isDirectory,
            sizeBytes = size,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        if (usesNativeHostFileOperations()) {
            return discoverNative(root, path, recursive = true)
                .asSequence()
                .filter {
                    matcher.matches(
                        FileSystems.getDefault().getPath(it.path).normalizeForMatch()
                    )
                }
                .take(config.maxListEntries)
                .toList()
        }
        val start = resolveExistingPathNoFollow(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        return walk(start) { paths ->
            paths
                .filter {
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
                }
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
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        if (usesNativeHostFileOperations()) {
            return grepCandidates(
                root = root,
                candidates = discoverNative(root, path, recursive = true)
                    .asSequence()
                    .filterNot { it.isDirectory },
                matcher = matcher,
                includeMatcher = includeMatcher,
            )
        }

        val start = resolveExistingPathNoFollow(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        return walk(start) { paths ->
            val candidates = paths
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .mapNotNull { candidate ->
                    val attributes = Files.readAttributes(
                        candidate,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    if (!attributes.isRegularFile) return@mapNotNull null
                    val relativePath = candidate.toFile().relativePath(root)
                    WorkspaceFileEntry(
                        path = relativePath,
                        name = candidate.fileName.toString(),
                        isDirectory = false,
                        sizeBytes = attributes.size(),
                        updatedAt = attributes.lastModifiedTime().toMillis(),
                    )
                }
            grepCandidates(root, candidates, matcher, includeMatcher)
        }
    }

    private fun grepCandidates(
        root: File,
        candidates: Sequence<WorkspaceFileEntry>,
        matcher: Regex,
        includeMatcher: java.nio.file.PathMatcher?,
    ): List<WorkspaceSearchMatch> {
        val results = mutableListOf<WorkspaceSearchMatch>()
        for (candidate in candidates) {
            if (results.size >= config.maxSearchResults) break
            if (includeMatcher != null &&
                !includeMatcher.matches(
                    FileSystems.getDefault().getPath(candidate.path).normalizeForMatch()
                )
            ) {
                continue
            }
            if (candidate.sizeBytes > config.maxReadBytes) continue
            val content = ByteArrayOutputStream(candidate.sizeBytes.toInt())
            exportNoFollow(
                root = root,
                path = candidate.path,
                outputStream = content,
                maxBytes = config.maxReadBytes,
            )
            content.toString(StandardCharsets.UTF_8.name())
                .lineSequence()
                .forEachIndexed { index, line ->
                    if (results.size >= config.maxSearchResults) return@forEachIndexed
                    if (matcher.containsMatchIn(line)) {
                        results += WorkspaceSearchMatch(
                            path = candidate.path,
                            line = index + 1,
                            text = line,
                        )
                    }
                }
        }
        return results
    }

    private fun discoverNative(
        root: File,
        path: String,
        recursive: Boolean,
    ): List<WorkspaceFileEntry> {
        val relativePath = if (path.isBlank() || path == ".") {
            ""
        } else {
            path.strictRelativeFileSegments().joinToString("/")
        }
        val records = requireNotNull(
            RootfsHostFileBridge.discoverEntries(
                root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                relativePath.toByteArray(StandardCharsets.UTF_8),
                recursive,
                MAX_DISCOVERY_SCAN_ENTRIES,
            )
        ) { "Path does not exist: $path" }
        return records.mapNotNull(::decodeNativeEntry)
    }

    internal fun discoverForSnapshot(root: File): List<WorkspaceFileEntry> {
        val entries = if (usesNativeHostFileOperations()) {
            val records = requireNotNull(
                RootfsHostFileBridge.discoverEntries(
                    root.absolutePath.toByteArray(StandardCharsets.UTF_8),
                    byteArrayOf(),
                    true,
                    MAX_DISCOVERY_SCAN_ENTRIES,
                )
            ) { "Workspace files root does not exist" }
            records.map { record ->
                requireNotNull(decodeNativeEntry(record)) {
                    "Workspace snapshot contains a symbolic link or special file"
                }
            }
        } else {
            require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Workspace files root must be a real directory"
            }
            walk(root) { paths ->
                paths.drop(1)
                    .map { path ->
                        val attributes = Files.readAttributes(
                            path,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        require(attributes.isDirectory || attributes.isRegularFile) {
                            "Workspace snapshot contains a link or special file: $path"
                        }
                        path.toFile().toEntry(root)
                    }
                    .take(MAX_DISCOVERY_SCAN_ENTRIES + 1)
                    .toList()
            }
        }
        require(entries.size <= MAX_DISCOVERY_SCAN_ENTRIES) {
            "Workspace snapshot contains too many entries"
        }
        return entries.sortedWith(
            compareBy<WorkspaceFileEntry> { it.path.count { char -> char == '/' } }
                .thenBy { !it.isDirectory }
                .thenBy { it.path },
        )
    }

    private fun decodeNativeEntry(record: ByteArray): WorkspaceFileEntry? {
        if (record.size <= NATIVE_ENTRY_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get().toInt()
        if (type != NATIVE_ENTRY_FILE && type != NATIVE_ENTRY_DIRECTORY) return null
        val size = buffer.long
        val updatedAt = buffer.long
        if (size < 0 || updatedAt < 0) return null
        val pathBytes = ByteArray(buffer.remaining())
        buffer.get(pathBytes)
        val path = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(pathBytes))
                .toString()
        }.getOrNull() ?: return null
        if (runCatching { path.strictRelativeFileSegments() }.isFailure) return null
        return WorkspaceFileEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = type == NATIVE_ENTRY_DIRECTORY,
            sizeBytes = if (type == NATIVE_ENTRY_FILE) size else 0,
            updatedAt = updatedAt,
        )
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

    private fun resolveExistingPathNoFollow(root: File, path: String): File {
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace root must be a real directory"
        }
        if (path.isBlank() || path == ".") return root.toPath().toAbsolutePath().normalize().toFile()
        val segments = path.strictRelativeFileSegments()
        var current = root.toPath().toAbsolutePath().normalize()
        segments.forEachIndexed { index, segment ->
            current = current.resolve(segment)
            require(Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                "Path does not exist: $path"
            }
            require(!Files.isSymbolicLink(current)) {
                "Refusing workspace access through symbolic link: $path"
            }
            if (index != segments.lastIndex) {
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Path component is not a directory: $segment"
                }
            }
        }
        return current.toFile()
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry {
        val attributes = Files.readAttributes(
            toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(!attributes.isSymbolicLink) { "Refusing workspace entry through symbolic link" }
        return WorkspaceFileEntry(
            path = relativePath(root),
            name = name,
            isDirectory = attributes.isDirectory,
            sizeBytes = if (attributes.isRegularFile) attributes.size() else 0L,
            updatedAt = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun File.relativePath(root: File): String {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = (parentFile ?: root).canonicalFile.toPath().resolve(name).normalize()
        require(targetPath.startsWith(rootPath)) { "Path escapes workspace root" }
        return rootPath.relativize(targetPath).toString().replace(File.separatorChar, '/')
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

    private fun List<String>.isPrefixOf(other: List<String>): Boolean =
        size < other.size && indices.all { this[it] == other[it] }

    private companion object {
        const val MAX_DESCRIPTOR_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_DISCOVERY_SCAN_ENTRIES = 100_000
        const val MAX_IMPORT_CONFLICTS = 10_000
        const val NATIVE_ENTRY_HEADER_BYTES = 17
        const val NATIVE_ENTRY_FILE = 1
        const val NATIVE_ENTRY_DIRECTORY = 2
    }
}
