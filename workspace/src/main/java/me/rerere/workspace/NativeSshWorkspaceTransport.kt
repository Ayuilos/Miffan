package me.rerere.workspace

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Credentials are supplied by the caller when a session opens; do not persist them in this module. */
sealed interface RemoteAuthentication {
    data class Password(val value: String) : RemoteAuthentication {
        override fun toString(): String = "Password([redacted])"
    }
    data class PrivateKey(val pem: String, val passphrase: String? = null) : RemoteAuthentication {
        override fun toString(): String = "PrivateKey([redacted])"
    }
}

data class RemoteHostConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authentication: RemoteAuthentication,
    val trustedHostKeySha256: String,
    val remoteRoot: String,
) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '\u0000' }) {
            "Invalid SSH host"
        }
        require(port in 1..65535) { "Invalid SSH port" }
        require(username.isNotBlank() && username.none { it == '\u0000' || it == '\n' }) {
            "Invalid SSH username"
        }
        require(trustedHostKeySha256.matches(Regex("SHA256:[A-Za-z0-9+/]{43}"))) {
            "A confirmed SHA-256 host key fingerprint is required"
        }
        RemoteWorkspacePath(remoteRoot)
    }
}

data class RemoteHostKey(val algorithm: String, val sha256Fingerprint: String)

/** SSH authentication succeeded, but the selected project directory cannot be used. */
class RemoteWorkspaceDirectoryException(cause: Throwable) :
    IllegalStateException("Remote workspace directory is inaccessible", cause)

/**
 * Direct Java SSH/SFTP transport. [discoverHostKey] is for a UI confirmation flow: its result is
 * untrusted until the user compares it with a separately obtained server fingerprint.
 */
object NativeSshWorkspaceTransport {
    fun discoverHostKey(host: String, port: Int = 22, timeoutMillis: Int = 10_000): RemoteHostKey {
        require(host.isNotBlank() && port in 1..65535 && timeoutMillis > 0)
        val repository = FingerprintHostKeyRepository(null)
        val jsch = JSch().apply { hostKeyRepository = repository }
        val session = jsch.getSession("miffan-host-key-discovery", host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "none")
        try {
            session.connect(timeoutMillis)
        } catch (_: JSchException) {
            // Strict checking intentionally rejects the untrusted key before authentication.
        } finally {
            session.disconnect()
        }
        return requireNotNull(repository.seenKey) { "Server did not present an SSH host key" }
    }

    fun open(config: RemoteHostConfig, timeoutMillis: Int = 10_000): RemoteWorkspaceSession {
        require(timeoutMillis > 0)
        val jsch = JSch().apply {
            hostKeyRepository = FingerprintHostKeyRepository(config.trustedHostKeySha256)
        }
        when (val auth = config.authentication) {
            is RemoteAuthentication.PrivateKey -> {
                require(auth.pem.isNotBlank()) { "Private key is empty" }
                jsch.addIdentity(
                    "miffan-remote-key",
                    auth.pem.toByteArray(StandardCharsets.UTF_8),
                    null,
                    auth.passphrase?.toByteArray(StandardCharsets.UTF_8),
                )
            }
            is RemoteAuthentication.Password -> require(auth.value.isNotEmpty()) { "Password is empty" }
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            when (config.authentication) {
                is RemoteAuthentication.Password -> "password"
                is RemoteAuthentication.PrivateKey -> "publickey"
            },
        )
        if (config.authentication is RemoteAuthentication.Password) {
            session.setPassword(config.authentication.value)
        }
        session.timeout = 60_000
        session.setServerAliveInterval(10_000)
        try {
            session.connect(timeoutMillis)
        } catch (error: Throwable) {
            session.disconnect()
            throw error
        }
        return try {
            RemoteWorkspaceSession(session, RemoteWorkspacePath(config.remoteRoot), timeoutMillis)
                .also { workspace ->
                    try {
                        workspace.verifyRoot()
                    } catch (error: SftpException) {
                        if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE ||
                            error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED
                        ) throw RemoteWorkspaceDirectoryException(error)
                        throw error
                    } catch (error: IllegalArgumentException) {
                        throw RemoteWorkspaceDirectoryException(error)
                    }
                }
        } catch (error: Throwable) {
            session.disconnect()
            throw error
        }
    }
}

class RemoteWorkspaceSession internal constructor(
    private val session: Session,
    private var paths: RemoteWorkspacePath,
    private val channelTimeoutMillis: Int,
) : Closeable {
    private val closed = AtomicBoolean(false)

    val isConnected: Boolean get() = !closed.get() && session.isConnected
    val resolvedRoot: String get() = paths.root

    internal fun verifyRoot() = withSftp { sftp ->
        // A selected root may itself be reached through a host symlink (for example macOS /var).
        // Resolve it once; all subsequent file paths still reject links below the selected root.
        paths = RemoteWorkspacePath(resolveSelectedRoot(sftp, paths.root), aliasRoot = paths.root)
        requireDirectoryPath(sftp, paths.root)
    }

    private fun resolveSelectedRoot(sftp: ChannelSftp, root: String): String {
        val remaining = java.util.ArrayDeque(root.split('/').filter { it.isNotEmpty() })
        val resolved = ArrayList<String>()
        var links = 0
        while (remaining.isNotEmpty()) {
            when (val segment = remaining.removeFirst()) {
                ".", "" -> continue
                ".." -> {
                    require(resolved.isNotEmpty()) { "Remote root symlink escapes filesystem root" }
                    resolved.removeAt(resolved.lastIndex)
                }
                else -> {
                    val candidate = "/" + (resolved + segment).joinToString("/")
                    val attrs = sftp.lstat(candidate)
                    if (attrs.isLink) {
                        require(++links <= 20) { "Too many symbolic links in remote workspace root" }
                        val target = sftp.readlink(candidate)
                        if (target.startsWith('/')) resolved.clear()
                        target.split('/').asReversed().forEach(remaining::addFirst)
                    } else {
                        require(attrs.isDir || remaining.isEmpty()) {
                            "Remote workspace root parent is not a directory: $candidate"
                        }
                        resolved += segment
                    }
                }
            }
        }
        return if (resolved.isEmpty()) "/" else "/" + resolved.joinToString("/")
    }

    /**
     * Executes in the selected remote directory. A timeout closes the SSH channel; SSH does not
     * guarantee that the remote process is terminated when its channel is closed.
     */
    @Synchronized
    fun execute(
        command: String,
        workingDirectory: String = "",
        timeoutMillis: Long = 30_000,
        maxOutputBytes: Int = 1_048_576,
        stdin: InputStream? = null,
    ): WorkspaceCommandResult {
        require(timeoutMillis > 0 && maxOutputBytes > 0)
        val directory = paths.absolute(workingDirectory, allowRoot = true)
        withSftp { requireDirectoryPath(it, directory) }
        checkOpen()
        val budget = AtomicInteger(maxOutputBytes)
        val stdout = LimitedOutputStream(budget)
        val stderr = LimitedOutputStream(budget)
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand("cd ${shellQuote(directory)} || exit 1\n$command")
        channel.setOutputStream(stdout)
        channel.setErrStream(stderr)
        if (stdin != null) channel.setInputStream(stdin) else channel.setInputStream(null)
        var timedOut = false
        try {
            channel.connect(channelTimeoutMillis)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (!channel.isClosed) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    break
                }
                Thread.sleep(20)
            }
            return WorkspaceCommandResult(
                exitCode = if (timedOut) -1 else channel.exitStatus,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = timedOut,
                truncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            channel.disconnect()
        }
    }

    /**
     * Opens a persistent interactive SSH PTY in the selected remote directory. The returned
     * terminal owns this SSH session: closing either object ends the terminal connection.
     */
    @Synchronized
    fun openTerminal(columns: Int = 80, rows: Int = 24): RemoteTerminalSession {
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        var channel: ChannelExec? = null
        try {
            withSftp { requireDirectoryPath(it, paths.root) }
            checkOpen()
            channel = session.openChannel("exec") as ChannelExec
            channel.setPty(true)
            channel.setPtyType("xterm-256color", columns, rows, 0, 0)
            channel.setEnv("TERM", "xterm-256color")
            channel.setCommand(
                "cd ${shellQuote(paths.root)} || exit 1\nexec \"\${SHELL:-/bin/sh}\" -i",
            )
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(channelTimeoutMillis)
            return RemoteTerminalSession(this, channel, input, output)
        } catch (error: Throwable) {
            runCatching { channel?.disconnect() }
            runCatching { close() }
            throw error
        }
    }

    @Synchronized
    fun list(path: String = ""): List<WorkspaceFileEntry> = withSftp { sftp ->
        val directory = paths.absolute(path, allowRoot = true)
        requireDirectoryPath(sftp, directory)
        val entries = ArrayList<ChannelSftp.LsEntry>(MAX_LIST_ENTRIES)
        sftp.ls(directory, ChannelSftp.LsEntrySelector { item ->
            if (item.filename != "." && item.filename != "..") entries += item
            if (entries.size >= MAX_LIST_ENTRIES) ChannelSftp.LsEntrySelector.BREAK
            else ChannelSftp.LsEntrySelector.CONTINUE
        })
        entries.asSequence()
            .filterNot { it.attrs.isLink }
            .mapNotNull { item ->
                if (!item.attrs.isDir && !item.attrs.isReg) return@mapNotNull null
                val relative = paths.relative(path, allowRoot = true)
                entry(if (relative.isEmpty()) item.filename else "$relative/${item.filename}", item.attrs)
            }
            .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    @Synchronized
    fun fileSize(path: String): Long = withSftp { sftp ->
        val attrs = requireSafePath(sftp, paths.absolute(path), expectFile = true)
        attrs.size
    }

    @Synchronized
    fun readText(path: String, maxBytes: Long = 512 * 1024): String =
        String(readBytes(path, maxBytes), StandardCharsets.UTF_8)

    @Synchronized
    fun readBytes(path: String, maxBytes: Long = 512 * 1024): ByteArray {
        val output = ByteArrayOutputStream()
        exportFile(path, output, maxBytes)
        return output.toByteArray()
    }

    @Synchronized
    fun exportFile(path: String, out: OutputStream, maxBytes: Long = Long.MAX_VALUE) {
        require(maxBytes >= 0)
        withSftp { sftp ->
            val absolute = paths.absolute(path)
            val attrs = requireSafePath(sftp, absolute, expectFile = true)
            require(attrs.size <= maxBytes) { "Remote file exceeds read limit" }
            sftp.get(absolute).use { input -> copyBounded(input, out, maxBytes) }
        }
    }

    @Synchronized
    fun writeText(path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry =
        writeBytes(path, text.toByteArray(StandardCharsets.UTF_8), overwrite)

    @Synchronized
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true): WorkspaceFileEntry =
        importFile(path, ByteArrayInputStream(bytes), overwrite, bytes.size.toLong())

    @Synchronized
    fun importFile(
        path: String,
        input: InputStream,
        overwrite: Boolean = true,
        maxBytes: Long = 32L * 1024 * 1024,
    ): WorkspaceFileEntry = withSftp { sftp ->
        require(maxBytes >= 0)
        val relative = paths.relative(path)
        val absolute = paths.absolute(path)
        ensureParents(sftp, absolute)
        val existing = optionalAttrs(sftp, absolute)
        require(existing == null || (overwrite && existing.isReg && !existing.isLink)) {
            "Remote file exists or is not a regular file: $path"
        }
        val staging = "$absolute.miffan-upload-${UUID.randomUUID()}"
        val backup = "$absolute.miffan-backup-${UUID.randomUUID()}"
        var previousMoved = false
        try {
            sftp.put(BoundedInputStream(input, maxBytes), staging, ChannelSftp.OVERWRITE)
            requireSafePath(sftp, staging, expectFile = true)
            if (existing != null) {
                sftp.chmod(existing.permissions and 0x1FF, staging)
                sftp.rename(absolute, backup)
                previousMoved = true
            }
            try {
                sftp.rename(staging, absolute)
            } catch (error: Throwable) {
                if (previousMoved) sftp.rename(backup, absolute)
                throw error
            }
            if (previousMoved) sftp.rm(backup)
            entry(relative, requireSafePath(sftp, absolute, expectFile = true))
        } finally {
            runCatching { if (optionalAttrs(sftp, staging) != null) sftp.rm(staging) }
        }
    }

    @Synchronized
    fun mkdir(path: String) = withSftp { sftp ->
        val absolute = paths.absolute(path)
        ensureDirectory(sftp, absolute)
    }

    @Synchronized
    fun delete(path: String, recursive: Boolean = false): Boolean = withSftp { sftp ->
        val absolute = paths.absolute(path)
        val attrs = optionalAttrs(sftp, absolute) ?: return@withSftp false
        requireSafePath(sftp, absolute)
        if (attrs.isDir) removeDirectory(sftp, absolute, recursive) else sftp.rm(absolute)
        true
    }

    @Synchronized
    fun move(from: String, to: String, overwrite: Boolean = false): WorkspaceFileEntry = withSftp { sftp ->
        val source = paths.absolute(from)
        val destination = paths.absolute(to)
        require(source != destination) { "Source and destination are the same" }
        val sourceAttrs = requireSafePath(sftp, source)
        ensureParents(sftp, destination)
        val existing = optionalAttrs(sftp, destination)
        require(existing == null || overwrite) { "Remote destination exists: $to" }
        if (existing != null) requireSafePath(sftp, destination)
        val backup = "$destination.miffan-backup-${UUID.randomUUID()}"
        if (existing != null) sftp.rename(destination, backup)
        try {
            sftp.rename(source, destination)
        } catch (error: Throwable) {
            if (existing != null) sftp.rename(backup, destination)
            throw error
        }
        if (existing != null) {
            if (existing.isDir) removeDirectory(sftp, backup, recursive = true)
            else sftp.rm(backup)
        }
        entry(paths.relative(to), sourceAttrs)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) session.disconnect()
    }

    private fun checkOpen() = check(isConnected) { "Remote workspace is disconnected" }

    private inline fun <T> withSftp(block: (ChannelSftp) -> T): T {
        checkOpen()
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(channelTimeoutMillis)
            return block(channel)
        } finally {
            channel.disconnect()
        }
    }

    private fun requireDirectoryPath(sftp: ChannelSftp, absolute: String) {
        require(requireSafePath(sftp, absolute).isDir) { "Remote path is not a directory: $absolute" }
    }

    private fun requireSafePath(
        sftp: ChannelSftp,
        absolute: String,
        expectFile: Boolean = false,
    ): SftpATTRS {
        // The selected root is user-controlled and may be reached through a host alias/symlink.
        // Refuse links only in paths selected by the assistant below that root.
        val relative = absolute.removePrefix(paths.root).trimStart('/')
        val rootAttrs = sftp.stat(paths.root)
        require(rootAttrs.isDir) { "Remote workspace root is not a directory" }
        var current = paths.root
        var attrs = rootAttrs
        val segments = if (relative.isEmpty()) emptyList() else relative.split('/')
        segments.forEachIndexed { index, segment ->
            current = if (current == "/") "/$segment" else "$current/$segment"
            attrs = sftp.lstat(current)
            require(!attrs.isLink) { "Refusing symbolic link in remote workspace path: $current" }
            if (index < segments.lastIndex) {
                require(attrs.isDir) { "Remote path parent is not a directory: $current" }
            }
        }
        return attrs.also {
            if (expectFile) require(it.isReg) { "Remote path is not a regular file: $absolute" }
        }
    }

    private fun optionalAttrs(sftp: ChannelSftp, absolute: String): SftpATTRS? = try {
        sftp.lstat(absolute)
    } catch (error: SftpException) {
        if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw error
    }

    private fun ensureParents(sftp: ChannelSftp, absolute: String) {
        ensureDirectory(sftp, absolute.substringBeforeLast('/', "/").ifEmpty { "/" })
    }

    private fun ensureDirectory(sftp: ChannelSftp, absolute: String) {
        requireDirectoryPath(sftp, paths.root)
        val relative = absolute.removePrefix(paths.root).trimStart('/')
        var current = paths.root
        if (relative.isEmpty()) return
        relative.split('/').forEach { segment ->
            current = if (current == "/") "/$segment" else "$current/$segment"
            val attrs = optionalAttrs(sftp, current)
            if (attrs == null) sftp.mkdir(current)
            else require(attrs.isDir && !attrs.isLink) { "Remote directory is a link or file: $current" }
        }
    }

    private fun removeDirectory(sftp: ChannelSftp, absolute: String, recursive: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val children = sftp.ls(absolute) as List<ChannelSftp.LsEntry>
        val present = children.filterNot { it.filename == "." || it.filename == ".." }
        require(recursive || present.isEmpty()) { "Remote directory is not empty" }
        present.forEach { child ->
            val childPath = "$absolute/${child.filename}"
            val attrs = requireSafePath(sftp, childPath)
            if (attrs.isDir) removeDirectory(sftp, childPath, true) else sftp.rm(childPath)
        }
        sftp.rmdir(absolute)
    }

    private fun entry(relative: String, attrs: SftpATTRS) = WorkspaceFileEntry(
        path = relative,
        name = relative.substringAfterLast('/'),
        isDirectory = attrs.isDir,
        sizeBytes = if (attrs.isReg) attrs.size else 0,
        updatedAt = attrs.mTime.toLong() * 1000,
    )
}

/** One persistent remote PTY. [input] is remote output; [output] sends keyboard bytes. */
class RemoteTerminalSession internal constructor(
    private val owner: RemoteWorkspaceSession,
    private val channel: ChannelExec,
    val input: InputStream,
    val output: OutputStream,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val controlLock = Any()

    val isConnected: Boolean get() = !closed.get() && owner.isConnected && channel.isConnected && !channel.isClosed
    val exitStatus: Int get() = channel.exitStatus

    fun resize(columns: Int, rows: Int) {
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        synchronized(controlLock) {
            check(isConnected) { "Remote terminal is disconnected" }
            channel.setPtySize(columns, rows, 0, 0)
        }
    }

    /** A literal ETX byte; with an SSH PTY this is the terminal's Ctrl-C input. */
    fun sendCtrlC() {
        synchronized(controlLock) {
            check(isConnected) { "Remote terminal is disconnected" }
            output.write(3)
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channel.disconnect()
            } finally {
                runCatching { input.close() }
                runCatching { output.close() }
                owner.close()
            }
        }
    }
}

private class FingerprintHostKeyRepository(private val trusted: String?) : HostKeyRepository {
    var seenKey: RemoteHostKey? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val algorithm = try { HostKey(host, key).type } catch (_: JSchException) { "unknown" }
        seenKey = RemoteHostKey(algorithm, fingerprint)
        return if (trusted == fingerprint) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
    override fun remove(host: String, type: String?) = Unit
    override fun remove(host: String, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "Miffan pinned host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class LimitedOutputStream(private val budget: AtomicInteger) : OutputStream() {
    private val output = ByteArrayOutputStream()
    @Volatile var truncated: Boolean = false
        private set

    @Synchronized override fun write(value: Int) {
        if (budget.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) output.write(value)
        else truncated = true
    }

    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val allowed = budget.getAndUpdate { (it - length).coerceAtLeast(0) }.coerceAtMost(length)
        output.write(bytes, offset, allowed)
        if (allowed < length) truncated = true
    }

    @Synchronized fun text(): String = output.toString(StandardCharsets.UTF_8.name())
}

private class BoundedInputStream(input: InputStream, private val maxBytes: Long) : InputStream() {
    private val source = input
    private var readBytes = 0L
    override fun read(): Int {
        val value = source.read()
        if (value >= 0 && ++readBytes > maxBytes) throw IllegalArgumentException("Remote upload exceeds limit")
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = source.read(bytes, offset, length)
        if (count > 0 && (readBytes + count).also { readBytes = it } > maxBytes) {
            throw IllegalArgumentException("Remote upload exceeds limit")
        }
        return count
    }
}

private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        require(total <= maxBytes) { "Remote download exceeds limit" }
        output.write(buffer, 0, count)
    }
}

private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

private const val MAX_LIST_ENTRIES = 500
