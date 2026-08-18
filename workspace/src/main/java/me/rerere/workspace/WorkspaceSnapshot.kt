package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption

internal class WorkspaceSnapshot(
    private val fileSystem: WorkspaceFileSystem,
    private val limits: WorkspaceResourceLimits,
) {
    fun export(root: File, outputStream: OutputStream) {
        val entries = fileSystem.discoverForSnapshot(root)
        val filesBytes = entries.fold(0L) { total, entry ->
            Math.addExact(total, if (entry.isDirectory) 0 else entry.sizeBytes)
        }
        require(filesBytes <= limits.maxFilesBytes) {
            "Workspace snapshot exceeds files quota: $filesBytes bytes"
        }
        DataOutputStream(BufferedOutputStream(outputStream)).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            entries.forEach { entry ->
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Workspace snapshot export cancelled")
                }
                val pathBytes = entry.path.toByteArray(StandardCharsets.UTF_8)
                require(pathBytes.size in 1..MAX_PATH_BYTES) {
                    "Workspace snapshot path is too long: ${entry.path}"
                }
                output.writeByte(if (entry.isDirectory) TYPE_DIRECTORY else TYPE_FILE)
                output.writeInt(pathBytes.size)
                output.write(pathBytes)
                if (!entry.isDirectory) {
                    output.writeLong(entry.sizeBytes)
                    fileSystem.exportNoFollow(
                        root = root,
                        path = entry.path,
                        outputStream = output,
                        maxBytes = limits.maxShellFileBytes,
                    )
                }
            }
            output.writeByte(TYPE_END)
        }
    }

    fun encodedSize(root: File): Long = fileSystem.discoverForSnapshot(root).fold(HEADER_AND_END_BYTES) {
        total, entry ->
        val pathBytes = entry.path.toByteArray(StandardCharsets.UTF_8).size
        require(pathBytes in 1..MAX_PATH_BYTES) { "Workspace snapshot path is too long: ${entry.path}" }
        Math.addExact(
            total,
            ENTRY_HEADER_BYTES + pathBytes + if (entry.isDirectory) 0 else FILE_SIZE_BYTES + entry.sizeBytes,
        )
    }

    fun import(root: File, inputStream: InputStream) {
        DataInputStream(BufferedInputStream(inputStream)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid workspace snapshot magic" }
            require(input.readInt() == VERSION) { "Unsupported workspace snapshot version" }
            var entries = 0
            var filesBytes = 0L
            val seenPaths = HashSet<String>()
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Workspace snapshot import cancelled")
                }
                when (val type = input.readUnsignedByte()) {
                    TYPE_END -> {
                        require(input.read() == -1) { "Workspace snapshot has trailing data" }
                        return
                    }

                    TYPE_DIRECTORY, TYPE_FILE -> {
                        entries++
                        require(entries <= MAX_ENTRIES) { "Workspace snapshot has too many entries" }
                        val path = input.readSnapshotPath()
                        require(seenPaths.add(path)) { "Duplicate workspace snapshot entry: $path" }
                        if (type == TYPE_DIRECTORY) {
                            root.ensureDirectoryNoFollow(path)
                        } else {
                            val size = input.readLong()
                            require(size in 0..limits.maxShellFileBytes) {
                                "Workspace snapshot file exceeds per-file limit: $path ($size bytes)"
                            }
                            filesBytes = Math.addExact(filesBytes, size)
                            require(filesBytes <= limits.maxFilesBytes) {
                                "Workspace snapshot exceeds files quota: $filesBytes bytes"
                            }
                            fileSystem.importExact(root, path, size, input)
                        }
                    }

                    else -> error("Unknown workspace snapshot entry type: $type")
                }
            }
        }
    }

    private fun DataInputStream.readSnapshotPath(): String {
        val length = readInt()
        require(length in 1..MAX_PATH_BYTES) { "Invalid workspace snapshot path length: $length" }
        val bytes = ByteArray(length)
        try {
            readFully(bytes)
        } catch (error: EOFException) {
            throw EOFException("Workspace snapshot ended inside a path").also { it.initCause(error) }
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .also { path ->
                require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) {
                    "Workspace snapshot path is ambiguous"
                }
                val segments = path.split('/')
                require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                    "Workspace snapshot path escapes its root"
                }
                require(segments.all { it.toByteArray(StandardCharsets.UTF_8).size <= 255 }) {
                    "Workspace snapshot path segment is too long"
                }
            }
    }

    companion object {
        private const val MAGIC = 0x524B5753
        private const val VERSION = 1
        private const val TYPE_END = 0
        private const val TYPE_DIRECTORY = 1
        private const val TYPE_FILE = 2
        private const val MAX_PATH_BYTES = 4096
        private const val MAX_ENTRIES = 100_000
        private const val HEADER_AND_END_BYTES = 9L
        private const val ENTRY_HEADER_BYTES = 5L
        private const val FILE_SIZE_BYTES = 8L
    }
}
