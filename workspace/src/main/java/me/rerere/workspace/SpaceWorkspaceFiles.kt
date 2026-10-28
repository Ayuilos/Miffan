package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rerere.cuaspace.SpaceClient
import me.rerere.cuaspace.SpaceEntry
import me.rerere.cuaspace.SpaceGrpcException
import me.rerere.cuaspace.SpacePty

/**
 * A workspace rooted at [root] inside a cua Space, reached through [client]. Mirrors the SSH
 * implementation's path rules and limits: no symlinks, no escaping the root, dirs listed first.
 * Calls block their thread (an interrupt cancels them), like the SFTP implementation.
 */
class SpaceWorkspaceFiles(
    private val client: SpaceClient,
    root: String = DEFAULT_ROOT,
) : RemoteWorkspaceFiles {
    private val paths = RemoteWorkspacePath(root)

    override val resolvedRoot: String get() = paths.root

    override fun list(path: String): List<WorkspaceFileEntry> = call {
        val directory = paths.absolute(path, allowRoot = true)
        val relativeDirectory = paths.relative(path, allowRoot = true)
        requireDirectory(directory)
        client.list(directory).asSequence()
            .filter { (it.isDirectory || it.isFile) && !it.isSymlink }
            .take(MAX_LIST_ENTRIES)
            .map { it.toWorkspaceEntry(if (relativeDirectory.isEmpty()) it.name else "$relativeDirectory/${it.name}") }
            .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    override fun fileSize(path: String): Long = call { requireFile(paths.absolute(path)).sizeBytes }

    override fun readText(path: String, maxBytes: Long): String = String(readBytes(path, maxBytes), Charsets.UTF_8)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        exportFile(path, output, maxBytes)
        return output.toByteArray()
    }

    override fun exportFile(path: String, out: OutputStream, maxBytes: Long) {
        require(maxBytes >= 0)
        call {
            val absolute = paths.absolute(path)
            require(requireFile(absolute).sizeBytes <= maxBytes) { "Remote file exceeds read limit" }
            client.readTo(absolute, maxBytes) { out.write(it) }
        }
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        writeBytes(path, text.toByteArray(Charsets.UTF_8), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean): WorkspaceFileEntry =
        importFile(path, bytes.inputStream(), overwrite, bytes.size.toLong())

    override fun importFile(path: String, input: InputStream, overwrite: Boolean, maxBytes: Long): WorkspaceFileEntry {
        require(maxBytes >= 0)
        val relative = paths.relative(path)
        val absolute = paths.absolute(path)
        val bytes = readBounded(input, maxBytes)
        return call {
            client.stat(absolute)?.let { existing ->
                require(overwrite) { "Remote destination exists: $path" }
                require(existing.isFile && !existing.isSymlink) { "Remote path is not a regular file: $path" }
            }
            client.write(absolute, bytes, overwrite).toWorkspaceEntry(relative)
        }
    }

    override fun mkdir(path: String) = call {
        val absolute = paths.absolute(path)
        client.stat(absolute)?.let { require(it.isDirectory && !it.isSymlink) { "Remote path is not a directory: $path" } }
            ?: client.mkdir(absolute, parents = true)
        Unit
    }

    override fun delete(path: String, recursive: Boolean): Boolean = call {
        val absolute = paths.absolute(path)
        val existing = client.stat(absolute) ?: return@call false
        require(!existing.isSymlink) { "Refusing to follow a symbolic link: $path" }
        client.remove(absolute, recursive = recursive && existing.isDirectory)
        true
    }

    override fun move(from: String, to: String, overwrite: Boolean): WorkspaceFileEntry = call {
        val source = paths.absolute(from)
        val destination = paths.absolute(to)
        require(source != destination) { "Source and destination are the same" }
        val moving = client.stat(source) ?: throw IllegalArgumentException("Remote path does not exist: $from")
        require(!moving.isSymlink) { "Refusing to follow a symbolic link: $from" }
        client.stat(destination)?.let {
            require(overwrite) { "Remote destination exists: $to" }
            require(!it.isSymlink) { "Refusing to follow a symbolic link: $to" }
        }
        val moved = client.move(source, destination, overwrite) ?: client.stat(destination) ?: moving
        moved.toWorkspaceEntry(paths.relative(to))
    }

    override fun execute(
        command: String,
        workingDirectory: String,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        stdin: InputStream?,
    ): WorkspaceCommandResult {
        require(timeoutMillis > 0 && maxOutputBytes > 0)
        val directory = paths.absolute(workingDirectory, allowRoot = true)
        val input = stdin?.let { readBounded(it, MAX_STDIN_BYTES) }
        return call {
            requireDirectory(directory)
            val result = client.run(
                command = RemoteShell.posixExecute(directory, "${RemoteShell.utf8Locale}\n$command"),
                cwd = directory,
                timeoutMillis = timeoutMillis,
                stdin = input,
                maxOutputBytes = maxOutputBytes,
            )
            WorkspaceCommandResult(result.exitCode, result.stdout, result.stderr, result.timedOut, result.truncated)
        }
    }

    override fun openTerminal(
        columns: Int,
        rows: Int,
        command: RemoteTerminalCommandSpec?,
        persistentCommands: Boolean,
    ): RemoteTerminal {
        require(columns in 1..4096 && rows in 1..4096) { "Invalid terminal size" }
        val script = command?.shellScript(paths.root) ?: RemoteShell.posixTerminal(paths.root, persistentCommands)
        call { requireDirectory(paths.root) }
        val pty = client.openPty("/bin/sh", listOf("-c", script), paths.root, columns, rows)
        try {
            // Hand out the terminal only once the process runs, so the first bytes written reach it.
            call { pty.awaitStarted() }
        } catch (error: Throwable) {
            pty.close()
            throw error
        }
        return SpaceTerminal(pty)
    }

    private suspend fun requireDirectory(absolute: String) {
        val entry = client.stat(absolute) ?: throw RemoteWorkspaceDirectoryException(
            IllegalArgumentException("Remote directory does not exist: $absolute"))
        require(entry.isDirectory && !entry.isSymlink) { "Remote path is not a directory: $absolute" }
    }

    private suspend fun requireFile(absolute: String): SpaceEntry {
        val entry = client.stat(absolute) ?: throw IllegalArgumentException("Remote file does not exist: $absolute")
        require(!entry.isSymlink) { "Refusing to follow a symbolic link: $absolute" }
        require(entry.isFile) { "Remote path is not a regular file: $absolute" }
        return entry
    }

    private fun <T> call(block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) { block() }
    } catch (error: SpaceGrpcException) {
        when (error.status) {
            SpaceGrpcException.NOT_FOUND, SpaceGrpcException.ALREADY_EXISTS, SpaceGrpcException.INVALID_ARGUMENT,
            SpaceGrpcException.FAILED_PRECONDITION, SpaceGrpcException.PERMISSION_DENIED ->
                throw IllegalArgumentException(error.message, error)
            else -> throw error
        }
    }

    private fun SpaceEntry.toWorkspaceEntry(relative: String) = WorkspaceFileEntry(
        path = relative,
        name = relative.substringAfterLast('/'),
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0 else sizeBytes,
        updatedAt = modifiedAtMillis,
    )

    private class SpaceTerminal(private val pty: SpacePty) : RemoteTerminal {
        override val input: InputStream get() = pty.input
        override val output: OutputStream get() = pty.output
        override val isConnected: Boolean get() = pty.isConnected
        override val exitStatus: Int get() = pty.exitCode
        override fun resize(columns: Int, rows: Int) = pty.resize(columns, rows)

        /** A literal ETX byte, the PTY's Ctrl-C, as on SSH. */
        override fun sendCtrlC() {
            check(isConnected) { "Remote terminal is disconnected" }
            output.write(3)
            output.flush()
        }

        override fun close() = pty.close()
    }

    companion object {
        /** The home of the image's `cua` user; partners work here. */
        const val DEFAULT_ROOT = "/home/cua"
        private const val MAX_LIST_ENTRIES = 500
        private const val MAX_STDIN_BYTES = 32L * 1024 * 1024

        private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "Upload exceeds the size limit" }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
}
