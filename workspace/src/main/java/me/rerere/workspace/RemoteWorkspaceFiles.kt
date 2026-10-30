package me.rerere.workspace

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Files, commands and terminals under one remote workspace root. Paths are relative to the root
 * (or `/workspace/...`). Implemented over SFTP/SSH by [RemoteWorkspaceSession] and inside a cua
 * Space by [SpaceWorkspaceFiles]. All calls block; run them off the main thread.
 */
interface RemoteWorkspaceFiles {
    val resolvedRoot: String
    fun list(path: String = ""): List<WorkspaceFileEntry>
    fun fileSize(path: String): Long
    fun readText(path: String, maxBytes: Long = 512 * 1024): String
    fun readBytes(path: String, maxBytes: Long = 512 * 1024): ByteArray
    fun exportFile(path: String, out: OutputStream, maxBytes: Long = Long.MAX_VALUE)
    fun writeText(path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true): WorkspaceFileEntry
    fun importFile(
        path: String,
        input: InputStream,
        overwrite: Boolean = true,
        maxBytes: Long = 32L * 1024 * 1024,
    ): WorkspaceFileEntry
    fun mkdir(path: String)
    fun delete(path: String, recursive: Boolean = false): Boolean
    fun move(from: String, to: String, overwrite: Boolean = false): WorkspaceFileEntry
    fun execute(
        command: String,
        workingDirectory: String = "",
        timeoutMillis: Long = 30_000,
        maxOutputBytes: Int = 1_048_576,
        stdin: InputStream? = null,
    ): WorkspaceCommandResult
    fun openTerminal(
        columns: Int = 80,
        rows: Int = 24,
        command: RemoteTerminalCommandSpec? = null,
        persistentCommands: Boolean = false,
    ): RemoteTerminal
}

/** An interactive PTY; the caller owns the connection it runs on. */
interface RemoteTerminal : Closeable {
    val input: InputStream
    val output: OutputStream
    val isConnected: Boolean
    val exitStatus: Int
    fun resize(columns: Int, rows: Int)
    fun sendCtrlC()
}
