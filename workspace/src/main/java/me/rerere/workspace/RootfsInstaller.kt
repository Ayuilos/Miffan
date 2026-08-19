package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val limits: RootfsInstallLimits = RootfsInstallLimits(),
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun install(
        root: String,
        source: RootfsArchiveSource,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = manager.withExclusiveAccess(root, interruptible = true) {
        manager.ensureWorkspace(root)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${source.format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        // Keep rollback data outside the routinely-cleaned temp directory. If the app process dies
        // between the two renames, the next install can still restore the previous Rootfs.
        val backupDir = File(manager.workspaceDir(root), ROOTFS_BACKUP_DIR)
        val linuxDir = manager.linuxDir(root)
        recoverInterruptedSwap(linuxDir, backupDir)
        repairInstalledRootfsMountPoints(linuxDir)

        try {
            archive.delete()
            stagingDir.deleteAsChildNoFollow()
            tempDir.ensureDirectoryNoFollow(stagingDir.name)
            // Reserve against both the per-workspace temp quota and the device free-space floor
            // before accepting attacker-amplifiable archive data.
            manager.requireAdditionalCapacity(
                root,
                WorkspaceDiskArea.TEMP,
                limits.maxDownloadBytes,
            )
            download(source, archive, onProgress)
            manager.requireAdditionalCapacity(
                root,
                WorkspaceDiskArea.TEMP,
                limits.maxExtractedBytes,
            )
            extractTar(archive, stagingDir, source.format, onProgress)
            patcher.patch(stagingDir)
            validateRootfs(stagingDir)
            val stagedRootfsBytes = stagingDir.logicalTreeSize()
            if (stagedRootfsBytes > manager.resourceLimits.maxRootfsBytes) {
                throw WorkspaceResourceLimitException(
                    "Installed Rootfs exceeds limit: $stagedRootfsBytes bytes used, " +
                        "${manager.resourceLimits.maxRootfsBytes} bytes allowed"
                )
            }
            manager.checkResourceLimits(root)
            swapRootfs(stagingDir, linuxDir, backupDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteAsChildNoFollow()
        }
    }

    /** Downloads a pinned archive for callers that broker installation across a file descriptor. */
    fun downloadVerifiedArchive(
        source: RootfsArchiveSource,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs download target already exists"
        }
        download(source, target, onProgress)
    }

    /** Installs an archive received through a brokered descriptor and verifies it again. */
    fun installFromArchive(
        root: String,
        source: RootfsArchiveSource,
        inputStream: InputStream,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) = manager.withExclusiveAccess(root, interruptible = true) {
        manager.ensureWorkspace(root)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${source.format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val backupDir = File(manager.workspaceDir(root), ROOTFS_BACKUP_DIR)
        val linuxDir = manager.linuxDir(root)
        recoverInterruptedSwap(linuxDir, backupDir)
        repairInstalledRootfsMountPoints(linuxDir)

        try {
            archive.delete()
            stagingDir.deleteAsChildNoFollow()
            tempDir.ensureDirectoryNoFollow(stagingDir.name)
            manager.requireAdditionalCapacity(root, WorkspaceDiskArea.TEMP, limits.maxDownloadBytes)
            copyAndVerifyArchive(source, inputStream, archive)
            manager.requireAdditionalCapacity(root, WorkspaceDiskArea.TEMP, limits.maxExtractedBytes)
            extractTar(archive, stagingDir, source.format, onProgress)
            patcher.patch(stagingDir)
            validateRootfs(stagingDir)
            val stagedRootfsBytes = stagingDir.logicalTreeSize()
            if (stagedRootfsBytes > manager.resourceLimits.maxRootfsBytes) {
                throw WorkspaceResourceLimitException(
                    "Installed Rootfs exceeds limit: $stagedRootfsBytes bytes used, " +
                        "${manager.resourceLimits.maxRootfsBytes} bytes allowed"
                )
            }
            manager.checkResourceLimits(root)
            swapRootfs(stagingDir, linuxDir, backupDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteAsChildNoFollow()
        }
    }

    private fun copyAndVerifyArchive(
        source: RootfsArchiveSource,
        inputStream: InputStream,
        target: File,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        require(Files.isDirectory(requireNotNull(target.parentFile).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs archive directory must not be a symbolic link"
        }
        var bytesRead = 0L
        try {
            inputStream.use { input ->
                Channels.newOutputStream(
                    Files.newByteChannel(
                        target.toPath(),
                        setOf<OpenOption>(
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE_NEW,
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                    )
                ).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        bytesRead = Math.addExact(bytesRead, read.toLong())
                        require(bytesRead <= limits.maxDownloadBytes) {
                            "Rootfs archive exceeds download limit: ${limits.maxDownloadBytes} bytes"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(bytesRead > 0) { "Rootfs archive is empty" }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualDigest == source.sha256) {
                "Rootfs SHA-256 mismatch (expected ${source.sha256}, got $actualDigest)"
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun download(
        source: RootfsArchiveSource,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val originalUrl = URL(source.url).also(::requireHttps)
        var currentUrl = originalUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = connectionFactory(currentUrl)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    require(redirectCount < MAX_REDIRECTS) { "Too many Rootfs download redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Rootfs redirect is missing Location")
                    currentUrl = URL(currentUrl, location).also { redirected ->
                        requireHttps(redirected)
                        require(redirected.host.equals(originalUrl.host, ignoreCase = true)) {
                            "Rootfs redirect changed host: ${redirected.host}"
                        }
                    }
                    return@repeat
                }
                require(code in 200..299) { "Rootfs download failed: HTTP $code" }
                downloadBody(connection, source, target, onProgress)
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Too many Rootfs download redirects")
    }

    private fun downloadBody(
        connection: HttpURLConnection,
        source: RootfsArchiveSource,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
        require(totalBytes == null || totalBytes <= limits.maxDownloadBytes) {
            "Rootfs archive exceeds download limit: $totalBytes bytes"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        require(Files.isDirectory(requireNotNull(target.parentFile).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs download directory must not be a symbolic link"
        }
        connection.inputStream.use { input ->
            Channels.newOutputStream(
                Files.newByteChannel(
                    target.toPath(),
                    setOf<OpenOption>(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE_NEW,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            ).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead = 0L
                var lastReportBytes = 0L
                while (true) {
                    checkInterrupted()
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytesRead += read
                    require(bytesRead <= limits.maxDownloadBytes) {
                        "Rootfs archive exceeds download limit: ${limits.maxDownloadBytes} bytes"
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                        lastReportBytes = bytesRead
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = bytesRead,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
                if (bytesRead == 0L) {
                    onProgress(
                        RootfsInstallProgress(
                            stage = RootfsInstallStage.DOWNLOADING,
                            bytesRead = 0,
                            totalBytes = totalBytes,
                        )
                    )
                }
            }
        }
        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualDigest == source.sha256) {
            "Rootfs SHA-256 mismatch (expected ${source.sha256}, got $actualDigest)"
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val archiveInput = Channels.newInputStream(
            Files.newByteChannel(
                archive.toPath(),
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            )
        )
        val extractionFiles = rootfsExtractionFiles(targetDir)
        format.wrapStream(BufferedInputStream(archiveInput)).use { input ->
            var entries = 0
            var extractedBytes = 0L
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    require(header.size <= limits.maxMetadataEntryBytes) { "Tar metadata entry is too large" }
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    require(header.size <= limits.maxMetadataEntryBytes) { "Tar metadata entry is too large" }
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    require(header.size <= limits.maxMetadataEntryBytes) { "Tar metadata entry is too large" }
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val relativePath = normalizeTarPath(header.name)
                entries++
                require(entries <= limits.maxEntries) {
                    "Rootfs archive contains too many entries: ${limits.maxEntries}"
                }
                require(header.size <= limits.maxSingleEntryBytes) {
                    "Rootfs entry is too large: ${header.name} (${header.size} bytes)"
                }
                extractedBytes = Math.addExact(extractedBytes, header.size)
                require(extractedBytes <= limits.maxExtractedBytes) {
                    "Rootfs extracted size exceeds limit: ${limits.maxExtractedBytes} bytes"
                }
                when (header.type) {
                    TarEntryType.DIRECTORY -> extractionFiles.directory(relativePath)
                    TarEntryType.SYMLINK -> {
                        require(header.linkName.isNotBlank()) {
                            "Rootfs archive symlink target is blank: ${header.name}"
                        }
                        validateSymlinkTarget(relativePath, header.linkName)
                        extractionFiles.symlink(relativePath, header.linkName)
                    }
                    TarEntryType.HARDLINK -> {
                        require(header.linkName.isNotBlank()) {
                            "Rootfs archive hard-link target is blank: ${header.name}"
                        }
                        val linkedBytes = extractionFiles.hardLink(
                            relativePath,
                            normalizeTarPath(header.linkName),
                        )
                        extractedBytes = Math.addExact(extractedBytes, linkedBytes)
                        require(extractedBytes <= limits.maxExtractedBytes) {
                            "Rootfs extracted size exceeds limit: ${limits.maxExtractedBytes} bytes"
                        }
                    }
                    TarEntryType.FILE -> {
                        extractionFiles.openFile(relativePath, header.mode).use { output ->
                            input.copyExactly(output, header.size)
                        }
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 &&
                    (header.type == TarEntryType.FILE ||
                        header.type == TarEntryType.DIRECTORY ||
                        header.type == TarEntryType.HARDLINK)
                ) {
                    extractionFiles.setModifiedAt(
                        relativePath,
                        Math.multiplyExact(header.modTime, 1000),
                    )
                }
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun validateRootfs(rootfs: File) {
        RootfsHealth.requireHealthy(rootfs)
    }

    private fun repairInstalledRootfsMountPoints(linuxDir: File) {
        if (RootfsHealth.isHealthy(linuxDir)) {
            patcher.repairExecutionMountPoints(linuxDir)
        }
    }

    private fun recoverInterruptedSwap(linuxDir: File, backupDir: File) {
        if (!Files.exists(backupDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        if (RootfsHealth.isHealthy(linuxDir)) {
            backupDir.deleteAsChildNoFollow()
        } else {
            // ensureWorkspace may have recreated an empty linux directory after a process death.
            linuxDir.deleteAsChildNoFollow()
            require(backupDir.renameDirectoryNoFollow(linuxDir)) {
                "Failed to restore previous Rootfs installation"
            }
        }
    }

    private fun swapRootfs(stagingDir: File, linuxDir: File, backupDir: File) {
        backupDir.deleteAsChildNoFollow()
        val movedPrevious = Files.exists(linuxDir.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (movedPrevious) {
            require(linuxDir.renameDirectoryNoFollow(backupDir)) {
                "Failed to stage previous Rootfs for rollback"
            }
        }
        try {
            require(stagingDir.renameDirectoryNoFollow(linuxDir)) {
                "Failed to move Rootfs into workspace"
            }
            validateRootfs(linuxDir)
            if (movedPrevious) backupDir.deleteAsChildNoFollow()
        } catch (error: Throwable) {
            linuxDir.deleteAsChildNoFollow()
            if (movedPrevious && Files.exists(backupDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(backupDir.renameDirectoryNoFollow(linuxDir)) {
                    "Failed to roll back previous Rootfs"
                }
            }
            throw error
        }
    }

    private fun requireHttps(url: URL) {
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Rootfs source must use HTTPS: $url"
        }
    }

    private fun File.deleteAsChildNoFollow(): Boolean =
        requireNotNull(parentFile).deleteRelativeTreeNoFollow(name)

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun normalizeTarPath(path: String): String {
        val input = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
        require(input.isNotBlank()) { "Rootfs entry path is blank" }
        require(!input.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        val segments = input.split('/').filter { it.isNotBlank() && it != "." }
        require(segments.none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        require(segments.all { it.toByteArray(Charsets.UTF_8).size <= 255 }) {
            "Rootfs entry path segment is too long"
        }
        val normalized = segments.joinToString("/")
        require(normalized.isNotBlank()) { "Rootfs entry path is blank" }
        require(normalized.toByteArray(Charsets.UTF_8).size <= 4096) {
            "Rootfs entry path is too long"
        }
        return normalized
    }

    private fun validateSymlinkTarget(entryPath: String, linkName: String) {
        require(!linkName.contains('\u0000')) { "Rootfs symlink target contains invalid character" }
        require(linkName.toByteArray(Charsets.UTF_8).size <= 4096) {
            "Rootfs symlink target is too long"
        }
        if (linkName.startsWith('/')) return
        val resolved = entryPath.split('/').dropLast(1).toMutableList()
        linkName.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    require(resolved.isNotEmpty()) { "Symlink escapes rootfs: $entryPath" }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> {
                    require(segment.toByteArray(Charsets.UTF_8).size <= 255) {
                        "Rootfs symlink target segment is too long"
                    }
                    resolved += segment
                }
            }
        }
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val ROOTFS_BACKUP_DIR = ".linux-backup"
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
