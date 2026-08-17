package me.rerere.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

data class WorkspaceResourceLimits(
    val maxFilesBytes: Long = 512L * 1024 * 1024,
    val maxRootfsBytes: Long = 1536L * 1024 * 1024,
    // Includes the downloaded archive and extraction staging tree during Rootfs installation.
    val maxTempBytes: Long = 1280L * 1024 * 1024,
    val maxWorkspaceBytes: Long = 3L * 1024 * 1024 * 1024,
    val minFreeSpaceBytes: Long = 256L * 1024 * 1024,
    val maxToolOutputBytes: Long = 32L * 1024 * 1024,
    val maxToolOutputFileBytes: Long = 2L * 1024 * 1024,
    val maxShellFileBytes: Long = 256L * 1024 * 1024,
    /** Per guest process; aggregate enforcement still requires a kernel-backed execution backend. */
    val maxShellVirtualMemoryBytes: Long = 1536L * 1024 * 1024,
    /** CPU time per guest process, distinct from the command wall-clock timeout. */
    val maxShellCpuTimeSeconds: Long = 300,
    /** Maximum processes/threads sharing the Android app UID while a guest creates children. */
    val maxShellProcesses: Int = 256,
    val maxActiveSessions: Int = 3,
    val maxSessionsPerWorkspace: Int = 1,
    val sessionAcquireTimeoutMillis: Long = 5_000,
    val fullDiskCheckIntervalMillis: Long = 5_000,
) {
    init {
        require(maxFilesBytes > 0 && maxRootfsBytes > 0 && maxTempBytes > 0)
        require(maxWorkspaceBytes > 0 && minFreeSpaceBytes >= 0)
        require(maxToolOutputFileBytes > 0 && maxToolOutputBytes >= maxToolOutputFileBytes)
        require(maxShellFileBytes > 0 && maxShellVirtualMemoryBytes > 0)
        require(maxShellCpuTimeSeconds > 0 && maxShellProcesses > 0)
        require(maxActiveSessions > 0 && maxSessionsPerWorkspace in 1..maxActiveSessions)
        require(sessionAcquireTimeoutMillis >= 0 && fullDiskCheckIntervalMillis > 0)
    }
}

data class WorkspaceDiskUsage(
    val filesBytes: Long,
    val rootfsBytes: Long,
    val tempBytes: Long,
    val otherWorkspaceBytes: Long,
    val toolOutputBytes: Long,
    val usableSpaceBytes: Long,
) {
    val workspaceBytes: Long
        get() = listOf(filesBytes, rootfsBytes, tempBytes, otherWorkspaceBytes)
            .fold(0L, ::saturatedAdd)

    val managedBytes: Long
        get() = saturatedAdd(workspaceBytes, toolOutputBytes)
}

private fun saturatedAdd(left: Long, right: Long): Long =
    runCatching { Math.addExact(left, right) }.getOrDefault(Long.MAX_VALUE)

enum class WorkspaceDiskArea {
    FILES,
    ROOTFS,
    TEMP,
    TOOL_OUTPUTS,
    OTHER,
}

class WorkspaceResourceLimitException(message: String) : IllegalStateException(message)

class WorkspaceBusyException(message: String) : IllegalStateException(message)

fun interface WorkspaceResourceGuard {
    @Throws(WorkspaceResourceLimitException::class)
    fun check()
}

/** Process-local admission control shared by AI commands, maintenance, and interactive terminals. */
class WorkspaceSessionRegistry(
    private val limits: WorkspaceResourceLimits = WorkspaceResourceLimits(),
) {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val activeByWorkspace = mutableMapOf<String, Int>()
    private var activeTotal = 0

    @Throws(InterruptedException::class, WorkspaceBusyException::class)
    fun acquire(root: String): WorkspaceSessionLease {
        var remaining = TimeUnit.MILLISECONDS.toNanos(limits.sessionAcquireTimeoutMillis)
        lock.lockInterruptibly()
        try {
            while (!canAcquire(root)) {
                if (remaining <= 0) {
                    throw WorkspaceBusyException("Workspace execution limit reached; try again later")
                }
                remaining = changed.awaitNanos(remaining)
            }
            return grant(root)
        } finally {
            lock.unlock()
        }
    }

    fun tryAcquire(root: String): WorkspaceSessionLease? {
        lock.lock()
        return try {
            if (canAcquire(root)) grant(root) else null
        } finally {
            lock.unlock()
        }
    }

    fun activeSessions(root: String? = null): Int {
        lock.lock()
        return try {
            if (root == null) activeTotal else activeByWorkspace[root] ?: 0
        } finally {
            lock.unlock()
        }
    }

    private fun canAcquire(root: String): Boolean =
        activeTotal < limits.maxActiveSessions &&
            (activeByWorkspace[root] ?: 0) < limits.maxSessionsPerWorkspace

    private fun grant(root: String): WorkspaceSessionLease {
        activeTotal++
        activeByWorkspace[root] = (activeByWorkspace[root] ?: 0) + 1
        return WorkspaceSessionLease { release(root) }
    }

    private fun release(root: String) {
        lock.lock()
        try {
            val remaining = (activeByWorkspace[root] ?: 1) - 1
            if (remaining <= 0) activeByWorkspace.remove(root) else activeByWorkspace[root] = remaining
            activeTotal = (activeTotal - 1).coerceAtLeast(0)
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }
}

class WorkspaceSessionLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal fun File.logicalTreeSize(): Long {
    if (!exists()) return 0
    if (!isDirectory) return if (Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)) length() else 0
    return runCatching {
        Files.walk(toPath()).use { paths ->
            paths.iterator().asSequence().fold(0L) { total, path ->
                val size = runCatching {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.size(path) else 0L
                }.getOrDefault(0L)
                runCatching { Math.addExact(total, size) }.getOrDefault(Long.MAX_VALUE)
            }
        }
    }.getOrDefault(Long.MAX_VALUE)
}
