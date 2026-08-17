package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val maxFileSizeBytes: Long? = null,
    val maxCpuTimeSeconds: Long? = null,
    val maxVirtualMemoryBytes: Long? = null,
    val maxProcesses: Int? = null,
    val resourceGuard: WorkspaceResourceGuard? = null,
    val processSupervisor: WorkspaceProcessSupervisor? = null,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val shell = defaultShell()
        val process = ProcessBuilder(shell, "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        // HostShellRunner is the JVM/test fallback and has no trusted setsid boundary. Android
        // production execution uses ProotShellRunner, which is durably tracked below.
        return process.readResult(context.timeoutMillis, context.stdin, context.resourceGuard)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(
    timeoutMillis: Long,
    stdin: ByteArray? = null,
    resourceGuard: WorkspaceResourceGuard? = null,
    processRegistration: WorkspaceProcessRegistration? = null,
): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val startedAt = System.nanoTime()
        var finished = false
        var timedOut = false
        var resourceError: WorkspaceResourceLimitException? = null
        while (!finished) {
            val elapsed = System.nanoTime() - startedAt
            val remaining = timeoutNanos - elapsed
            if (remaining <= 0) {
                timedOut = true
                break
            }
            val pollMillis = minOf(
                RESOURCE_POLL_INTERVAL_MS,
                TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1),
            )
            finished = waitFor(pollMillis, TimeUnit.MILLISECONDS)
            if (!finished && resourceGuard != null) {
                try {
                    resourceGuard.check()
                } catch (error: WorkspaceResourceLimitException) {
                    resourceError = error
                    break
                }
            }
        }
        if (timedOut) {
            terminateProcessTree(graceful = true, processRegistration)
        } else if (resourceError != null) {
            terminateProcessTree(graceful = false, processRegistration)
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        val stderrText = buildString {
            append(stderr.text())
            resourceError?.let { error ->
                if (isNotEmpty() && !endsWith('\n')) appendLine()
                append("Resource limit exceeded: ${error.message}")
            }
        }
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderrText,
            timedOut = timedOut,
            truncated = stdout.truncated || stderr.truncated,
            resourceLimitExceeded = resourceError != null,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        terminateProcessTree(graceful = false, processRegistration)
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

internal fun Process.readTrackedResult(
    context: WorkspaceShellContext,
    isolatedProcessGroup: Boolean,
    commandIdentity: String,
): WorkspaceCommandResult {
    val supervisor = context.processSupervisor
        ?: return readResult(context.timeoutMillis, context.stdin, context.resourceGuard)
    val registration = try {
        supervisor.register(
            root = context.root,
            pid = reflectedPid(),
            isolatedProcessGroup = isolatedProcessGroup,
            commandIdentity = commandIdentity,
        )
    } catch (error: Throwable) {
        terminateProcessTree(graceful = false, processRegistration = null)
        throw error
    }
    if (registration == null) {
        return readResult(context.timeoutMillis, context.stdin, context.resourceGuard)
    }
    return registration.use {
        readResult(
            timeoutMillis = context.timeoutMillis,
            stdin = context.stdin,
            resourceGuard = context.resourceGuard,
            processRegistration = registration,
        )
    }
}

private fun Process.reflectedPid(): Long = runCatching {
    Process::class.java.getMethod("pid").invoke(this) as Long
}.getOrElse { error("Unable to inspect workspace process id") }

/** Best-effort descendant cleanup in addition to PRoot's --kill-on-exit behavior. */
private fun Process.terminateProcessTree(
    graceful: Boolean,
    processRegistration: WorkspaceProcessRegistration?,
) {
    // PRoot is launched as a process-group leader. Signal the verified group before the main PID,
    // so grandchildren cannot outlive cancellation merely by holding inherited descriptors.
    processRegistration?.terminate(graceful)
    // ProcessHandle is absent from some Android API stubs/runtimes. Reflection keeps this
    // best-effort cleanup available on runtimes that provide it without raising minSdk.
    val processHandleClass = runCatching { Class.forName("java.lang.ProcessHandle") }.getOrNull()
    val descendants: List<Any> = runCatching {
        val handleClass = requireNotNull(processHandleClass)
        val handle = Process::class.java.getMethod("toHandle").invoke(this)
        val stream = handleClass.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>
        stream.use { handles ->
            handles.iterator().asSequence().filterNotNull().toList().asReversed()
        }
    }.getOrDefault(emptyList())

    fun invoke(handle: Any, method: String): Any? = runCatching {
        requireNotNull(processHandleClass).getMethod(method).invoke(handle)
    }.getOrNull()

    if (graceful) {
        descendants.forEach { handle -> invoke(handle, "destroy") }
        runCatching { destroy() }
        runCatching { waitFor(PROCESS_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS) }
    }

    descendants.forEach { handle ->
        if (invoke(handle, "isAlive") == true) invoke(handle, "destroyForcibly")
    }
    if (isAlive) runCatching { destroyForcibly() }
    // destroyForcibly is asynchronous on the JVM and some Android runtimes. Do not report the
    // session as released while the main PRoot process is still in the process table.
    runCatching { waitFor(PROCESS_FORCE_WAIT_MS, TimeUnit.MILLISECONDS) }
}

private const val PROCESS_TERMINATION_GRACE_MS = 250L
private const val PROCESS_FORCE_WAIT_MS = 1_000L
private const val RESOURCE_POLL_INTERVAL_MS = 250L

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
