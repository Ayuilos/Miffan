package me.rerere.workspace

import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelWindow
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Credentials are supplied by the caller when a session opens; do not persist them in this module. */
sealed interface RemoteAuthentication {
    data class Password(val value: String) : RemoteAuthentication {
        override fun toString(): String = "Password([redacted])"
    }
    /** [filePath] is for local integration harnesses; Android persists only in-memory keys. */
    data class PrivateKey(val pem: String, val passphrase: String? = null, val filePath: String? = null) : RemoteAuthentication {
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

/**
 * The SSH connection to [host] could not be set up. Still a [JSchException] with the same message,
 * so existing checks keep working; the address lets the UI explain e.g. a Tailscale timeout.
 */
class RemoteConnectException(
    val host: String,
    cause: JSchException,
    /** Set when a password sign-in failed authentication; null for every other failure. */
    val passwordSignIn: RemotePasswordSignInFailure? = null,
    /** The account that tried to sign in, when the failure was authentication. */
    val username: String? = null,
) : JSchException(cause.message, cause)

enum class RemotePasswordSignInFailure {
    /** The server never asked for a password: it only allows other methods, usually keys. */
    NOT_OFFERED,
    /** The server asked and refused the password (or the username). */
    REJECTED,
}

/**
 * Answers the server's password requests, by the "password" method or a keyboard-interactive
 * password prompt, at most once per connection so a wrong password costs one attempt. Other
 * keyboard-interactive prompts (one-time codes and the like) are never sent the password.
 *
 * Declining a keyboard-interactive round still makes JSch send empty answers, which a server counts
 * as another failed attempt, so such rounds abort the connection with [PasswordSignInStopped]
 * instead. Declining the "password" method sends nothing.
 */
private class PasswordSignInStopped(val failure: RemotePasswordSignInFailure) : RuntimeException("Auth fail")

private class PasswordAnswer(private val password: String) : UserInfo, UIKeyboardInteractive {
    @Volatile var asked = false
        private set

    private fun take(): Boolean = if (asked) false else true.also { asked = true }

    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?): Boolean = take()
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?, name: String?, instruction: String?, prompt: Array<String>, echo: BooleanArray,
    ): Array<String> = when {
        // An empty round carries only instructions; answering it is not an attempt.
        prompt.isEmpty() -> emptyArray()
        prompt.size == 1 && !echo[0] && isPasswordPrompt(prompt[0]) && take() -> arrayOf(password)
        else -> throw PasswordSignInStopped(if (asked) RemotePasswordSignInFailure.REJECTED else RemotePasswordSignInFailure.NOT_OFFERED)
    }

    private fun isPasswordPrompt(text: String): Boolean =
        listOf("password", "passwort", "mot de passe", "contraseña", "пароль", "密码", "密碼", "パスワード", "암호", "비밀번호")
            .any { text.contains(it, ignoreCase = true) }
}

/** SSH authentication succeeded, but the selected project directory cannot be used. */
class RemoteWorkspaceDirectoryException(cause: Throwable) :
    IllegalStateException("Remote workspace directory is inaccessible", cause)

/**
 * Direct Java SSH/SFTP transport. [discoverHostKey] is for a UI confirmation flow: its result is
 * untrusted until the user compares it with a separately obtained server fingerprint.
 */
object NativeSshWorkspaceTransport {
    /** One key-only handshake, with ephemeral host-key acceptance and no SFTP or trust writes. */
    fun checkKeySignIn(
        host: String, port: Int, username: String, authentication: RemoteAuthentication.PrivateKey,
        timeoutMillis: Int = 6_000,
    ): KeySignInCheck {
        require(host.isNotBlank() && port in 1..65535 && username.isNotBlank() && timeoutMillis > 0)
        val jsch = JSch().apply {
            hostKeyRepository = FingerprintHostKeyRepository(null, acceptUntrusted = true)
            if (authentication.filePath != null) addIdentity(authentication.filePath)
            else addIdentity("miffan-key-probe", authentication.pem.toByteArray(StandardCharsets.UTF_8),
                null, authentication.passphrase?.toByteArray(StandardCharsets.UTF_8))
        }
        val session = jsch.getSession(username, host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.timeout = timeoutMillis
        val expired = AtomicBoolean(false)
        val deadline = Timer("miffan-key-probe", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { expired.set(true); session.disconnect() }
        }, timeoutMillis.toLong())
        return try {
            session.connect(timeoutMillis)
            if (expired.get()) KeySignInCheck.UNREACHABLE else KeySignInCheck.ACCEPTED
        } catch (error: JSchException) {
            when {
                expired.get() -> KeySignInCheck.UNREACHABLE
                isRemoteKeyAuthenticationRefused(error) -> KeySignInCheck.REFUSED
                isRemoteNetworkFailure(error) -> KeySignInCheck.UNREACHABLE
                else -> throw error
            }
        } finally {
            deadline.cancel()
            session.disconnect()
        }
    }

    fun discoverHostKey(
        host: String, port: Int = 22, timeoutMillis: Int = 10_000,
        socketFactory: SocketFactory? = null,
    ): RemoteHostKey {
        require(host.isNotBlank() && port in 1..65535 && timeoutMillis > 0)
        val repository = FingerprintHostKeyRepository(null)
        val jsch = JSch().apply { hostKeyRepository = repository }
        val session = jsch.getSession("miffan-host-key-discovery", host, port)
        socketFactory?.let { factory ->
            session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
                override fun createSocket(host: String, port: Int): Socket {
                    val socket = factory.createSocket()
                    try {
                        socket.connect(InetSocketAddress(host, port), timeoutMillis)
                        return socket
                    } catch (error: Throwable) {
                        runCatching { socket.close() }
                        throw error
                    }
                }
                override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })
        }
        session.timeout = timeoutMillis
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "none")
        var failure: JSchException? = null
        try {
            session.connect(timeoutMillis)
        } catch (error: JSchException) {
            // Strict checking intentionally rejects the untrusted key before authentication.
            failure = error
        } finally {
            session.disconnect()
        }
        // No key seen means the server was never reached; report why rather than a missing key.
        return repository.seenKey ?: throw failure?.let { RemoteConnectException(host, it) }
            ?: IllegalStateException("Server did not present an SSH host key")
    }

    fun open(
        config: RemoteHostConfig,
        timeoutMillis: Int = 10_000,
        sftpIdleTimeoutMillis: Long = 60_000,
    ): RemoteWorkspaceSession {
        require(sftpIdleTimeoutMillis > 0)
        require(timeoutMillis > 0)
        val jsch = JSch().apply {
            hostKeyRepository = FingerprintHostKeyRepository(config.trustedHostKeySha256)
        }
        when (val auth = config.authentication) {
            is RemoteAuthentication.PrivateKey -> {
                if (auth.filePath != null) jsch.addIdentity(auth.filePath) else {
                    require(auth.pem.isNotBlank()) { "Private key is empty" }
                    jsch.addIdentity(
                        "miffan-remote-key",
                        auth.pem.toByteArray(StandardCharsets.UTF_8),
                        null,
                        auth.passphrase?.toByteArray(StandardCharsets.UTF_8),
                    )
                }
            }
            is RemoteAuthentication.Password -> require(auth.value.isNotEmpty()) { "Password is empty" }
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            when (config.authentication) {
                is RemoteAuthentication.Password -> "password,keyboard-interactive"
                is RemoteAuthentication.PrivateKey -> "publickey"
            },
        )
        // Not setPassword: the answer object tells whether the server asked for a password at all.
        val passwordAnswer = (config.authentication as? RemoteAuthentication.Password)?.let { PasswordAnswer(it.value) }
        passwordAnswer?.let(session::setUserInfo)
        session.timeout = 60_000
        session.setServerAliveInterval(10_000)
        try {
            session.connect(timeoutMillis)
        } catch (error: Throwable) {
            session.disconnect()
            if (error is PasswordSignInStopped) throw RemoteConnectException(config.host, JSchException("Auth fail", error),
                error.failure, config.username)
            if (error !is JSchException) throw error
            // JSch reports both as "Auth fail" or, after a declined second prompt, "Auth cancel".
            val authFailed = error.message?.startsWith("Auth ") == true
            throw RemoteConnectException(config.host, error, passwordSignIn = passwordAnswer?.takeIf { authFailed }?.let {
                if (it.asked) RemotePasswordSignInFailure.REJECTED else RemotePasswordSignInFailure.NOT_OFFERED
            }, username = config.username.takeIf { authFailed })
        }
        return try {
            RemoteWorkspaceSession(session, RemoteWorkspacePath(config.remoteRoot), timeoutMillis, sftpIdleTimeoutMillis)
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
    private val sftpIdleTimeoutMillis: Long,
) : Closeable, RemoteWorkspaceFiles {
    private val closed = AtomicBoolean(false)
    private val activeChannels = ConcurrentHashMap<Thread, Channel>()
    private val operation = ThreadLocal<OperationHandle?>()

    class OperationHandle internal constructor() {
        private val lock = Any()
        private var channel: Channel? = null
        private var cancelled = false

        internal fun install(next: Channel) = synchronized(lock) {
            if (cancelled) {
                next.disconnect()
                throw java.io.InterruptedIOException("Remote operation cancelled")
            }
            channel = next
        }

        internal fun release(current: Channel) = synchronized(lock) {
            if (channel === current) channel = null
        }

        fun cancel() = synchronized(lock) {
            cancelled = true
            channel?.disconnect()
        }
    }

    fun newOperation(): OperationHandle = OperationHandle()

    fun <T> withOperation(handle: OperationHandle, block: () -> T): T {
        operation.set(handle)
        try { return block() } finally { operation.remove() }
    }

    val isConnected: Boolean get() = !closed.get() && session.isConnected
    override val resolvedRoot: String get() = paths.root
    var platform: RemotePlatform = RemotePlatform.POSIX
        private set
    var homeDirectory: String = "/"
        private set
    val supportsInteractiveTerminal: Boolean get() = platform.supportsInteractiveTerminal

    internal fun verifyRoot() = withSftp { sftp, _ ->
        homeDirectory = sftp.realpath(".")
        platform = RemotePlatform.detect(homeDirectory)
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
    override fun execute(
        command: String,
        workingDirectory: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        stdin: InputStream?,
    ): WorkspaceCommandResult {
        require(timeoutMillis > 0 && maxOutputBytes > 0)
        val directory = if (platform == RemotePlatform.WINDOWS) WindowsRemotePath.workingDirectory(paths.root,
                // A session without a workspace is rooted at "/", which is no Windows directory: start at home.
                workingDirectory.ifEmpty { if (paths.root == "/") homeDirectory else workingDirectory })
            else paths.absolute(workingDirectory, allowRoot = true)
        withSftp { sftp, _ -> requireDirectoryPath(sftp, directory) }
        checkOpen()
        val budget = AtomicInteger(maxOutputBytes)
        val stdout = LimitedOutputStream(budget)
        val stderr = LimitedOutputStream(budget)
        val channel = session.openChannel("exec") as ChannelExec
        register(channel)
        var timedOut = false
        try {
            val request = RemoteShell.request(platform, directory, command, stdin)
            channel.setCommand(request.command)
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.setInputStream(request.stdin)
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
                stdout = if (platform == RemotePlatform.WINDOWS) PowerShellOutput.readable(stdout.text()) else stdout.text(),
                stderr = if (platform == RemotePlatform.WINDOWS) PowerShellOutput.readable(stderr.text()) else stderr.text(),
                timedOut = timedOut,
                truncated = stdout.truncated || stderr.truncated,
            )
        } finally {
            channel.disconnect()
            unregister(channel)
        }
    }

    /**
     * Opens a persistent interactive SSH PTY in the selected remote directory. The returned
     * terminal owns only its PTY channel; the caller owns the parent SSH session.
     */
    override fun openTerminal(columns: Int, rows: Int, command: RemoteTerminalCommandSpec?,
        persistentCommands: Boolean): RemoteTerminalSession {
        if (!supportsInteractiveTerminal) throw RemoteTerminalUnsupportedException()
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        var channel: ChannelExec? = null
        try {
            val directory = paths.absolute(command?.cwd.orEmpty(), allowRoot = true)
            withSftp { sftp, _ -> requireDirectoryPath(sftp, directory) }
            checkOpen()
            channel = session.openChannel("exec") as ChannelExec
            register(channel)
            channel.setPty(true)
            channel.setPtyType("xterm-256color", columns, rows, 0, 0)
            channel.setEnv("TERM", "xterm-256color")
            channel.setCommand(
                command?.shellScript(paths.root, platform)
                    ?: RemoteShell.posixTerminal(paths.root, persistentCommands),
            )
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(channelTimeoutMillis)
            unregister(channel)
            return RemoteTerminalSession(this, channel, input, output)
        } catch (error: Throwable) {
            runCatching { channel?.disconnect() }
            channel?.let(::unregister)
            throw error
        }
    }

    /**
     * Opens a direct-tcpip stream to [port] on the remote host's loopback interface. Nothing
     * listens on the device, so other local apps cannot reach the forwarded service.
     */
    fun openLoopbackStream(port: Int, timeoutMillis: Int = channelTimeoutMillis): RemoteChannelStream {
        require(port in 1..65535) { "Invalid port" }
        require(timeoutMillis > 0)
        checkOpen()
        val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
        val owner = operation.get()
        try {
            owner?.install(channel)
            channel.setHost("127.0.0.1")
            channel.setPort(port)
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(0)
            ChannelWindow.widen(channel, STREAM_WINDOW_BYTES, STREAM_PACKET_BYTES)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(timeoutMillis)
            return RemoteChannelStream(this, channel, input, output, null)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        } finally { owner?.release(channel) }
    }

    /**
     * Opens an OpenSSH direct-streamlocal stream to the Unix socket at [path]. Socket file
     * permissions on the remote host decide who may connect, so a per-user runtime directory
     * keeps other accounts on that machine out.
     */
    fun openUnixSocketStream(path: String): RemoteChannelStream {
        require(path.startsWith("/") && path.none { it == '\u0000' || it == '\n' }) { "Invalid socket path" }
        checkOpen()
        val channel = session.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        try {
            channel.setSocketPath(path)
            ChannelWindow.widen(channel, STREAM_WINDOW_BYTES, STREAM_PACKET_BYTES)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(channelTimeoutMillis)
            return RemoteChannelStream(this, channel, input, output, null)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
    }

    /** Starts [command] without a PTY and exposes its stdio as a byte stream (e.g. MCP stdio). */
    fun openProcess(command: String): RemoteChannelStream {
        checkOpen()
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(if (platform == RemotePlatform.WINDOWS) RemoteShell.windowsProcessCommand else command)
            val input = channel.inputStream
            val output = channel.outputStream
            val stderr = channel.errStream
            channel.connect(channelTimeoutMillis)
            if (platform == RemotePlatform.WINDOWS) {
                output.write(RemoteShell.windowsFrame(paths.root, command))
                output.flush()
            }
            return RemoteChannelStream(this, channel, input, output, stderr)
        } catch (error: Throwable) {
            runCatching { channel.disconnect() }
            throw error
        }
    }

    override fun list(path: String): List<WorkspaceFileEntry> = withSftp { sftp, _ ->
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

    override fun fileSize(path: String): Long = withSftp { sftp, _ ->
        val attrs = requireSafePath(sftp, paths.absolute(path), expectFile = true)
        attrs.size
    }

    override fun readText(path: String, maxBytes: Long): String =
        String(readBytes(path, maxBytes), StandardCharsets.UTF_8)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        exportFile(path, output, maxBytes)
        return output.toByteArray()
    }

    override fun exportFile(path: String, out: OutputStream, maxBytes: Long) {
        require(maxBytes >= 0)
        withSftp { sftp, progress ->
            val absolute = paths.absolute(path)
            val attrs = requireSafePath(sftp, absolute, expectFile = true)
            require(attrs.size <= maxBytes) { "Remote file exceeds read limit" }
            sftp.get(absolute, progress).use { input -> copyBounded(input, out, maxBytes) }
        }
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        writeBytes(path, text.toByteArray(StandardCharsets.UTF_8), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean): WorkspaceFileEntry =
        importFile(path, ByteArrayInputStream(bytes), overwrite, bytes.size.toLong())

    override fun importFile(
        path: String,
        input: InputStream,
        overwrite: Boolean,
        maxBytes: Long,
    ): WorkspaceFileEntry {
        require(maxBytes >= 0)
        val relative = paths.relative(path)
        val absolute = paths.absolute(path)
        val staging = "$absolute.miffan-upload-${UUID.randomUUID()}"
        val backup = "$absolute.miffan-backup-${UUID.randomUUID()}"
        var stagingPending = false
        try {
            return withSftp { sftp, progress ->
                ensureParents(sftp, absolute)
                val existing = optionalAttrs(sftp, absolute)
                require(existing == null || (overwrite && existing.isReg && !existing.isLink)) {
                    "Remote file exists or is not a regular file: $path"
                }
                var previousMoved = false
                stagingPending = true
                sftp.put(BoundedInputStream(input, maxBytes), staging, progress, ChannelSftp.OVERWRITE)
                requireSafePath(sftp, staging, expectFile = true)
                if (existing != null) {
                    if (platform == RemotePlatform.POSIX) sftp.chmod(existing.permissions and 0x1FF, staging)
                    sftp.rename(absolute, backup)
                    previousMoved = true
                }
                try {
                    sftp.rename(staging, absolute)
                    stagingPending = false
                } catch (error: Throwable) {
                    if (previousMoved) sftp.rename(backup, absolute)
                    throw error
                }
                if (previousMoved) sftp.rm(backup)
                entry(relative, requireSafePath(sftp, absolute, expectFile = true))
            }
        } finally {
            // A failed streaming put can leave unread SFTP replies and an open file handle.
            // Close that channel first, then clean its staging file through a fresh channel.
            if (stagingPending) runCatching {
                withSftp { sftp, _ ->
                    if (optionalAttrs(sftp, staging) != null) {
                        requireSafePath(sftp, staging, expectFile = true)
                        sftp.rm(staging)
                    }
                }
            }
        }
    }

    override fun mkdir(path: String) = withSftp { sftp, _ ->
        val absolute = paths.absolute(path)
        ensureDirectory(sftp, absolute)
    }

    override fun delete(path: String, recursive: Boolean): Boolean = withSftp { sftp, _ ->
        val absolute = paths.absolute(path)
        val attrs = optionalAttrs(sftp, absolute) ?: return@withSftp false
        requireSafePath(sftp, absolute)
        if (attrs.isDir) removeDirectory(sftp, absolute, recursive) else sftp.rm(absolute)
        true
    }

    override fun move(from: String, to: String, overwrite: Boolean): WorkspaceFileEntry = withSftp { sftp, _ ->
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
        if (closed.compareAndSet(false, true)) {
            activeChannels.values.forEach { runCatching { it.disconnect() } }
            session.disconnect()
        }
    }

    private fun register(channel: Channel) {
        checkOpen()
        operation.get()?.install(channel)
        activeChannels[Thread.currentThread()] = channel
        if (!isConnected) {
            unregister(channel)
            channel.disconnect()
            checkOpen()
        }
    }

    private fun unregister(channel: Channel) {
        operation.get()?.release(channel)
        activeChannels.remove(Thread.currentThread(), channel)
    }

    private fun checkOpen() = check(isConnected) { "Remote workspace is disconnected" }

    private fun <T> withSftp(block: (ChannelSftp, SftpIdleGuard) -> T): T {
        checkOpen()
        val channel = session.openChannel("sftp") as ChannelSftp
        register(channel)
        return SftpIdleGuard(sftpIdleTimeoutMillis, channel::disconnect).run { progress ->
            try {
                channel.connect(channelTimeoutMillis)
                progress.touch()
                block(channel, progress)
            } finally {
                channel.disconnect()
                unregister(channel)
            }
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
    override val input: InputStream,
    override val output: OutputStream,
) : RemoteTerminal {
    private val closed = AtomicBoolean(false)
    private val controlLock = Any()

    val platform: RemotePlatform get() = owner.platform
    override val isConnected: Boolean get() = !closed.get() && owner.isConnected && channel.isConnected && !channel.isClosed
    override val exitStatus: Int get() = channel.exitStatus

    override fun resize(columns: Int, rows: Int) {
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        synchronized(controlLock) {
            check(isConnected) { "Remote terminal is disconnected" }
            channel.setPtySize(columns, rows, 0, 0)
        }
    }

    /** A literal ETX byte; with an SSH PTY this is the terminal's Ctrl-C input. */
    override fun sendCtrlC() {
        synchronized(controlLock) {
            check(isConnected) { "Remote terminal is disconnected" }
            output.write(3)
            output.flush()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeSshChannelOnIo(channel, input, output)
        }
    }
}

/** A raw SSH channel byte stream; the caller owns the parent SSH session. */
class RemoteChannelStream internal constructor(
    private val owner: RemoteWorkspaceSession,
    private val channel: Channel,
    val input: InputStream,
    val output: OutputStream,
    val errors: InputStream?,
) : Closeable {
    private val closed = AtomicBoolean(false)

    val platform: RemotePlatform get() = owner.platform
    val isConnected: Boolean get() = !closed.get() && owner.isConnected && channel.isConnected && !channel.isClosed

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeSshChannelOnIo(channel, input, output, errors)
        }
    }
}

private val sshChannelCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * JSch disconnect sends SSH CLOSE, and OutputStream.close may send EOF. A main-thread
 * network exception is swallowed by JSch after packet encoding has advanced cipher state,
 * leaving the shared SSH session unusable. Logical closure above is immediate; all network
 * cleanup runs on IO, independently of the cancelled viewer/terminal scope.
 */
private fun closeSshChannelOnIo(channel: Channel, input: InputStream, output: OutputStream, errors: InputStream? = null) {
    sshChannelCleanup.launch {
        try { channel.disconnect() } finally {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { errors?.close() }
        }
    }
}

private class FingerprintHostKeyRepository(private val trusted: String?, private val acceptUntrusted: Boolean = false) : HostKeyRepository {
    var seenKey: RemoteHostKey? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        val algorithm = try { HostKey(host, key).type } catch (_: JSchException) { "unknown" }
        seenKey = RemoteHostKey(algorithm, fingerprint)
        return if (acceptUntrusted || trusted == fingerprint) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
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

private const val MAX_LIST_ENTRIES = 500

/** Receive window for forwarded streams: 4 MiB keeps a LAN or Tailscale link full (128 KiB managed ~4 MB/s). */
private const val STREAM_WINDOW_BYTES = 4 * 1024 * 1024
private const val STREAM_PACKET_BYTES = 32 * 1024
