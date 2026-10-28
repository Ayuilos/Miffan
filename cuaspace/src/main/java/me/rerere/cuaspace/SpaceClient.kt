package me.rerere.cuaspace

import cua.env.v1.CloseStdinRequest
import cua.env.v1.CloseStdinResponse
import cua.env.v1.EntryInfo
import cua.env.v1.FileType
import cua.env.v1.HealthRequest
import cua.env.v1.HealthResponse
import cua.env.v1.ListDirRequest
import cua.env.v1.ListDirResponse
import cua.env.v1.MakeDirRequest
import cua.env.v1.MakeDirResponse
import cua.env.v1.MoveRequest
import cua.env.v1.MoveResponse
import cua.env.v1.ProcessConfig
import cua.env.v1.ProcessInput
import cua.env.v1.ProcessSelector
import cua.env.v1.PtyConfig
import cua.env.v1.PtySize
import cua.env.v1.ReadFileRequest
import cua.env.v1.ReadFileResponse
import cua.env.v1.RemoveRequest
import cua.env.v1.RemoveResponse
import cua.env.v1.ResizePtyRequest
import cua.env.v1.ResizePtyResponse
import cua.env.v1.SendInputRequest
import cua.env.v1.SendInputResponse
import cua.env.v1.Signal
import cua.env.v1.SignalProcessRequest
import cua.env.v1.SignalProcessResponse
import cua.env.v1.StartProcessRequest
import cua.env.v1.StartProcessResponse
import cua.env.v1.StatRequest
import cua.env.v1.StatResponse
import cua.env.v1.WriteFileHeader
import cua.env.v1.WriteFileRequest
import cua.env.v1.WriteFileResponse
import cua.env.v1.WriteMode
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString

/** One file or directory in a Space, with an absolute guest path. */
data class SpaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

data class SpaceRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/**
 * Talks to cua-spacesd inside one Space over [opener] (normally an SSH direct-tcpip channel to the
 * Space's loopback port on its host). Paths are absolute guest paths; callers map their own roots.
 */
class SpaceClient(
    opener: SpaceStreamOpener,
    port: Int,
    token: String,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    internal val http: OkHttpClient = baseClient.newBuilder()
        .socketFactory(ChannelSocketFactory(opener))
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    internal val baseUrl = "http://127.0.0.1:$port"
    private val grpc = SpaceGrpc(http, baseUrl, token)

    suspend fun health(): HealthResponse =
        grpc.unary("cua.env.v1.SystemService/Health", HealthRequest(), HealthResponse.ADAPTER)

    /**
     * Runs [command] through `/bin/sh -c` in [cwd] and waits for it. Output beyond [maxOutputBytes]
     * (stdout and stderr together) is dropped and reported as truncated.
     */
    suspend fun run(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray? = null,
        maxOutputBytes: Int = 1_048_576,
    ): SpaceRunResult = coroutineScope {
        require(timeoutMillis > 0 && maxOutputBytes > 0)
        val tag = "miffan-run-" + UUID.randomUUID()
        val request = StartProcessRequest(
            config = ProcessConfig(
                command = "/bin/sh",
                args = listOf("-c", command),
                cwd = cwd,
                timeout = Duration.ofMillis(timeoutMillis),
            ),
            tag = tag,
            stdin = stdin != null,
            kill_on_disconnect = true,
        )
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var budget = maxOutputBytes
        var truncated = false
        var exitCode = -1
        var timedOut = false
        // The server enforces the limit; this only guards against a stream that never ends.
        withTimeout(timeoutMillis + RUN_GRACE_MILLIS) {
            grpc.serverStream("cua.env.v1.ProcessService/StartProcess", request, StartProcessResponse.ADAPTER)
                .takeWhile { response ->
                    val event = response.event ?: return@takeWhile true
                    event.start?.let {
                        if (stdin != null) launch { sendAllInput(ProcessSelector(tag = tag), stdin) }
                    }
                    event.data_?.let { data ->
                        val bytes = (data.stdout ?: data.stderr ?: data.pty)?.toByteArray() ?: return@let
                        val kept = minOf(bytes.size, budget)
                        (if (data.stderr != null) stderr else stdout).write(bytes, 0, kept)
                        budget -= kept
                        truncated = truncated || kept < bytes.size
                    }
                    val end = event.end ?: return@takeWhile true
                    timedOut = end.timed_out
                    exitCode = when {
                        end.timed_out -> -1
                        end.exit_code != null -> end.exit_code
                        end.signal_number != 0 -> 128 + end.signal_number
                        else -> -1
                    }
                    if (end.error.isNotEmpty() && end.exit_code == null) {
                        stderr.write(end.error.toByteArray())
                    }
                    false
                }
                .collect {}
        }
        SpaceRunResult(exitCode, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()), timedOut, truncated)
    }

    /** Starts [command] under a PTY; the process dies when the returned handle closes. */
    fun openPty(command: String, args: List<String>, cwd: String, columns: Int, rows: Int): SpacePty {
        val tag = "miffan-pty-" + UUID.randomUUID()
        val request = StartProcessRequest(
            config = ProcessConfig(command = command, args = args, cwd = cwd),
            pty = PtyConfig(size = PtySize(cols = columns, rows = rows), term = "xterm-256color"),
            tag = tag,
            stdin = true,
            kill_on_disconnect = true,
        )
        return SpacePty(this, ProcessSelector(tag = tag)) {
            grpc.serverStream("cua.env.v1.ProcessService/StartProcess", request, StartProcessResponse.ADAPTER)
        }
    }

    internal suspend fun sendInput(process: ProcessSelector, bytes: ByteArray, pty: Boolean) {
        val input = if (pty) ProcessInput(pty = bytes.toByteString()) else ProcessInput(stdin = bytes.toByteString())
        grpc.unary("cua.env.v1.ProcessService/SendInput", SendInputRequest(process = process, input = input),
            SendInputResponse.ADAPTER)
    }

    internal suspend fun resize(process: ProcessSelector, columns: Int, rows: Int) {
        grpc.unary("cua.env.v1.ProcessService/ResizePty",
            ResizePtyRequest(process = process, size = PtySize(cols = columns, rows = rows)), ResizePtyResponse.ADAPTER)
    }

    internal suspend fun signal(process: ProcessSelector, signal: Signal) {
        grpc.unary("cua.env.v1.ProcessService/SignalProcess",
            SignalProcessRequest(process = process, signal = signal, process_group = true), SignalProcessResponse.ADAPTER)
    }

    private suspend fun sendAllInput(process: ProcessSelector, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + INPUT_CHUNK_BYTES)
            sendInput(process, bytes.copyOfRange(offset, end), pty = false)
            offset = end
        }
        grpc.unary("cua.env.v1.ProcessService/CloseStdin", CloseStdinRequest(process = process), CloseStdinResponse.ADAPTER)
    }

    suspend fun stat(path: String): SpaceEntry? = try {
        grpc.unary("cua.env.v1.FilesystemService/Stat", StatRequest(path = path), StatResponse.ADAPTER).entry?.toEntry()
    } catch (error: SpaceGrpcException) {
        if (error.isNotFound) null else throw error
    }

    suspend fun list(path: String): List<SpaceEntry> {
        val entries = mutableListOf<SpaceEntry>()
        var pageToken = ""
        do {
            val page = grpc.unary("cua.env.v1.FilesystemService/ListDir",
                ListDirRequest(path = path, depth = 1, include_hidden = true, page_size = 1000, page_token = pageToken),
                ListDirResponse.ADAPTER)
            page.entries.mapTo(entries) { it.toEntry() }
            pageToken = page.next_page_token
        } while (pageToken.isNotEmpty())
        return entries
    }

    /** Reads up to [maxBytes]; a longer file fails rather than being cut silently. */
    suspend fun read(path: String, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        readTo(path, maxBytes) { out.write(it) }
        return out.toByteArray()
    }

    /** Streams the file to [sink] chunk by chunk; fails once more than [maxBytes] arrive. */
    suspend fun readTo(path: String, maxBytes: Long, sink: (ByteArray) -> Unit) {
        var received = 0L
        // length 0 reads to the end; otherwise ask for one byte more to notice an oversized file.
        val length = if (maxBytes >= Long.MAX_VALUE - 1) 0L else maxBytes + 1
        grpc.serverStream("cua.env.v1.FilesystemService/ReadFile",
            ReadFileRequest(path = path, length = length), ReadFileResponse.ADAPTER)
            .collect { response ->
                response.entry?.let { if (it.type == FileType.FILE_TYPE_DIRECTORY) throw SpaceGrpcException(
                    SpaceGrpcException.FAILED_PRECONDITION, "$path is a directory") }
                val bytes = response.chunk?.data_?.toByteArray() ?: return@collect
                received += bytes.size
                if (received > maxBytes) throw SpaceGrpcException(SpaceGrpcException.FAILED_PRECONDITION,
                    "$path is larger than $maxBytes bytes")
                sink(bytes)
            }
    }

    suspend fun write(path: String, bytes: ByteArray, overwrite: Boolean, createParents: Boolean = true): SpaceEntry {
        val header = WriteFileHeader(
            path = path,
            mode = if (overwrite) WriteMode.WRITE_MODE_OVERWRITE else WriteMode.WRITE_MODE_CREATE_NEW,
            create_parents = createParents,
            expected_size = bytes.size.toLong(),
        )
        val chunks = (0 until bytes.size step WRITE_CHUNK_BYTES).map { start ->
            WriteFileRequest(data_ = bytes.copyOfRange(start, minOf(bytes.size, start + WRITE_CHUNK_BYTES)).toByteString())
        }
        val response = grpc.clientStream("cua.env.v1.FilesystemService/WriteFile",
            listOf(WriteFileRequest(header_ = header)) + chunks, WriteFileResponse.ADAPTER)
        return response.entry?.toEntry() ?: requireNotNull(stat(path)) { "$path was not written" }
    }

    suspend fun mkdir(path: String, parents: Boolean = true): SpaceEntry? =
        grpc.unary("cua.env.v1.FilesystemService/MakeDir", MakeDirRequest(path = path, parents = parents),
            MakeDirResponse.ADAPTER).entry?.toEntry()

    suspend fun remove(path: String, recursive: Boolean, missingOk: Boolean = false) {
        grpc.unary("cua.env.v1.FilesystemService/Remove",
            RemoveRequest(path = path, recursive = recursive, missing_ok = missingOk), RemoveResponse.ADAPTER)
    }

    suspend fun move(from: String, to: String, overwrite: Boolean): SpaceEntry? =
        grpc.unary("cua.env.v1.FilesystemService/Move",
            MoveRequest(source = from, destination = to, overwrite = overwrite, create_parents = true),
            MoveResponse.ADAPTER).entry?.toEntry()

    /** Releases idle HTTP/2 connections (their SSH channels). */
    fun close() {
        http.connectionPool.evictAll()
    }

    private fun EntryInfo.toEntry() = SpaceEntry(
        path = path,
        name = name.ifEmpty { path.substringAfterLast('/') },
        isDirectory = type == FileType.FILE_TYPE_DIRECTORY,
        isFile = type == FileType.FILE_TYPE_FILE,
        isSymlink = type == FileType.FILE_TYPE_SYMLINK,
        sizeBytes = size,
        modifiedAtMillis = modified_at?.toEpochMilli() ?: 0L,
    )

    private companion object {
        const val RUN_GRACE_MILLIS = 15_000L
        const val INPUT_CHUNK_BYTES = 256 * 1024
        const val WRITE_CHUNK_BYTES = 256 * 1024
    }
}
