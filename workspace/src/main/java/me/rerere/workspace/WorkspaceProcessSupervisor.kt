package me.rerere.workspace

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable ownership records for native workspace processes.
 *
 * Android does not automatically kill an app's native children when its Java process disappears.
 * Records include the kernel process start time (and boot id when readable), so recovery never
 * signals a PID based on its number alone.
 */
class WorkspaceProcessSupervisor internal constructor(
    private val stateDir: File,
    private val system: WorkspaceProcessSystem = ProcfsWorkspaceProcessSystem(),
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Synchronized
    fun recoverStaleProcesses(): WorkspaceProcessRecoveryReport {
        stateDir.mkdirs()
        var activeOwners = 0
        var terminated = 0
        var discarded = 0
        var remaining = 0

        stateDir.listFiles().orEmpty().forEach { marker ->
            if (marker.extension == TEMP_EXTENSION) {
                marker.delete()
                return@forEach
            }
            if (marker.extension != RECORD_EXTENSION || marker.length() > MAX_RECORD_BYTES) {
                marker.delete()
                discarded++
                return@forEach
            }
            val record = runCatching {
                json.decodeFromString<WorkspaceProcessRecord>(marker.readText(StandardCharsets.UTF_8))
            }.getOrNull()
            if (record == null || !record.isStructurallyValid()) {
                marker.delete()
                discarded++
                return@forEach
            }

            val currentBootId = system.bootId()
            if (record.bootId != null && currentBootId != null && record.bootId != currentBootId) {
                marker.delete()
                discarded++
                return@forEach
            }

            val target = system.snapshot(record.pid)
            if (target == null || !record.matchesTarget(target)) {
                marker.delete()
                discarded++
                return@forEach
            }

            val owner = system.snapshot(record.ownerPid)
            if (owner != null && record.matchesOwner(owner)) {
                activeOwners++
                return@forEach
            }

            // The recorded owner is gone, so there is no stateful caller left to coordinate a
            // graceful shutdown. Signal the verified process group before its leader can exit and
            // leave grandchildren behind.
            terminate(record, graceful = false)
            if (system.snapshot(record.pid)?.let(record::matchesTarget) == true) {
                remaining++
            } else {
                marker.delete()
                terminated++
            }
        }

        return WorkspaceProcessRecoveryReport(
            activeOwnerRecords = activeOwners,
            terminatedProcesses = terminated,
            discardedRecords = discarded,
            remainingProcesses = remaining,
        )
    }

    @Synchronized
    fun register(
        root: String,
        pid: Long,
        isolatedProcessGroup: Boolean,
        commandIdentity: String,
    ): WorkspaceProcessRegistration? {
        require(pid > 1 && pid <= Int.MAX_VALUE) { "Invalid workspace process id: $pid" }
        require(commandIdentity.isNotBlank()) { "Workspace process command identity is required" }
        stateDir.mkdirs()

        val ownerPid = system.currentPid()
        val owner = requireNotNull(system.snapshot(ownerPid)) {
            "Unable to inspect workspace process owner: $ownerPid"
        }
        val target = awaitRegistrationTarget(pid, isolatedProcessGroup, commandIdentity) ?: return null
        require(target.uid == owner.uid) { "Workspace process runs under an unexpected UID" }

        val id = UUID.randomUUID().toString()
        val marker = File(stateDir, "$id.$RECORD_EXTENSION")
        val record = WorkspaceProcessRecord(
            root = root,
            pid = pid,
            processStartTimeTicks = target.startTimeTicks,
            processGroupId = target.processGroupId,
            uid = target.uid,
            isolatedProcessGroup = isolatedProcessGroup,
            commandIdentity = commandIdentity,
            ownerPid = ownerPid,
            ownerStartTimeTicks = owner.startTimeTicks,
            ownerUid = owner.uid,
            ownerCommandLine = owner.commandLine,
            bootId = system.bootId(),
        )
        writeRecordAtomically(marker, record)
        return WorkspaceProcessRegistration(this, marker, record)
    }

    private fun awaitRegistrationTarget(
        pid: Long,
        isolatedProcessGroup: Boolean,
        commandIdentity: String,
    ): WorkspaceProcessSnapshot? {
        val deadline = System.nanoTime() + REGISTRATION_TIMEOUT_MILLIS * NANOS_PER_MILLI
        var lastSnapshot: WorkspaceProcessSnapshot? = null
        while (System.nanoTime() < deadline) {
            val snapshot = system.snapshot(pid) ?: return null
            lastSnapshot = snapshot
            if (
                snapshot.commandLine.contains(commandIdentity) &&
                (!isolatedProcessGroup || snapshot.processGroupId == pid)
            ) {
                return snapshot
            }
            try {
                system.sleep(REGISTRATION_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while registering workspace process")
            }
        }
        val finalSnapshot = requireNotNull(lastSnapshot) { "Unable to inspect workspace process: $pid" }
        require(finalSnapshot.commandLine.contains(commandIdentity)) {
            "Workspace process command does not match the expected executable"
        }
        if (isolatedProcessGroup) {
            require(finalSnapshot.processGroupId == pid) {
                "Workspace process did not enter its own process group"
            }
        }
        return finalSnapshot
    }

    internal fun terminate(record: WorkspaceProcessRecord, graceful: Boolean): Boolean {
        val current = system.snapshot(record.pid)
        if (current == null || !record.matchesTarget(current)) return true

        val signalTarget = if (record.isolatedProcessGroup) -record.processGroupId else record.pid
        if (graceful) {
            system.signal(signalTarget, SIGTERM)
            if (waitUntilGone(record, TERMINATION_GRACE_MILLIS)) return true
        }
        system.signal(signalTarget, SIGKILL)
        return waitUntilGone(record, TERMINATION_FORCE_MILLIS)
    }

    internal fun release(marker: File, record: WorkspaceProcessRecord) {
        // Keep the durable record if cleanup returned while the exact process is still alive.
        val current = system.snapshot(record.pid)
        if (current == null || !record.matchesTarget(current)) marker.delete()
    }

    private fun waitUntilGone(record: WorkspaceProcessRecord, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            val current = system.snapshot(record.pid)
            if (current == null || !record.matchesTarget(current)) return true
            try {
                system.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return system.snapshot(record.pid)?.let(record::matchesTarget) != true
    }

    private fun writeRecordAtomically(marker: File, record: WorkspaceProcessRecord) {
        val temp = File(stateDir, "${marker.name}.$TEMP_EXTENSION")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(WorkspaceProcessRecord.serializer(), record).toByteArray())
                output.fd.sync()
            }
            require(temp.renameTo(marker)) { "Unable to publish workspace process record" }
            require(system.syncDirectory(stateDir)) {
                "Unable to persist workspace process record directory"
            }
        } finally {
            temp.delete()
        }
    }

    private companion object {
        private const val RECORD_EXTENSION = "json"
        private const val TEMP_EXTENSION = "tmp"
        private const val MAX_RECORD_BYTES = 16L * 1024
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val TERMINATION_GRACE_MILLIS = 250L
        private const val TERMINATION_FORCE_MILLIS = 1_000L
        private const val POLL_MILLIS = 25L
        private const val REGISTRATION_TIMEOUT_MILLIS = 500L
        private const val REGISTRATION_POLL_MILLIS = 10L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

class WorkspaceProcessRegistration internal constructor(
    private val supervisor: WorkspaceProcessSupervisor,
    private val marker: File,
    internal val record: WorkspaceProcessRecord,
) : AutoCloseable {
    fun terminate(graceful: Boolean = false): Boolean = supervisor.terminate(record, graceful)

    override fun close() {
        // A first close can race with asynchronous process death and intentionally keep the
        // marker. A later terminal callback must be able to retry removal once the PID is gone.
        supervisor.release(marker, record)
    }
}

data class WorkspaceProcessRecoveryReport(
    val activeOwnerRecords: Int,
    val terminatedProcesses: Int,
    val discardedRecords: Int,
    val remainingProcesses: Int,
)

@Serializable
internal data class WorkspaceProcessRecord(
    val version: Int = RECORD_VERSION,
    val root: String,
    val pid: Long,
    val processStartTimeTicks: Long,
    val processGroupId: Long,
    val uid: Long,
    val isolatedProcessGroup: Boolean,
    val commandIdentity: String,
    val ownerPid: Long,
    val ownerStartTimeTicks: Long,
    val ownerUid: Long,
    val ownerCommandLine: String,
    val bootId: String?,
) {
    fun isStructurallyValid(): Boolean =
        version == RECORD_VERSION &&
            root.isNotBlank() &&
            pid > 1 && ownerPid > 1 &&
            processStartTimeTicks > 0 && ownerStartTimeTicks > 0 &&
            processGroupId > 0 && uid >= 0 && ownerUid >= 0 &&
            commandIdentity.isNotBlank() && ownerCommandLine.isNotBlank()

    fun matchesOwner(snapshot: WorkspaceProcessSnapshot): Boolean =
        snapshot.pid == ownerPid &&
            snapshot.startTimeTicks == ownerStartTimeTicks &&
            snapshot.uid == ownerUid &&
            snapshot.commandLine == ownerCommandLine

    fun matchesTarget(snapshot: WorkspaceProcessSnapshot): Boolean =
        snapshot.pid == pid &&
            snapshot.startTimeTicks == processStartTimeTicks &&
            snapshot.uid == uid &&
            snapshot.commandLine.contains(commandIdentity) &&
            (!isolatedProcessGroup || snapshot.processGroupId == processGroupId)

    internal companion object {
        private const val RECORD_VERSION = 1
    }
}

internal data class WorkspaceProcessSnapshot(
    val pid: Long,
    val startTimeTicks: Long,
    val processGroupId: Long,
    val uid: Long,
    val commandLine: String,
    val processName: String = "",
)

internal interface WorkspaceProcessSystem {
    fun currentPid(): Long
    fun bootId(): String?
    fun snapshot(pid: Long): WorkspaceProcessSnapshot?
    fun signal(pidOrProcessGroup: Long, signal: Int): Boolean
    fun syncDirectory(directory: File): Boolean
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)
}

internal class ProcfsWorkspaceProcessSystem(
    private val procRoot: File = File("/proc"),
) : WorkspaceProcessSystem {
    override fun currentPid(): Long {
        val androidPid = runCatching {
            val processClass = Class.forName("android.os.Process")
            (processClass.getMethod("myPid").invoke(null) as Int).toLong()
        }.getOrNull()
        if (androidPid != null && androidPid > 1) return androidPid
        return runCatching {
            val processHandleClass = Class.forName("java.lang.ProcessHandle")
            val current = processHandleClass.getMethod("current").invoke(null)
            processHandleClass.getMethod("pid").invoke(current) as Long
        }.getOrElse { error("Unable to determine current process id") }
    }

    override fun bootId(): String? =
        runCatching { File(procRoot, "sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    override fun snapshot(pid: Long): WorkspaceProcessSnapshot? {
        if (pid <= 1 || pid > Int.MAX_VALUE) return null
        val processDir = File(procRoot, pid.toString())
        val stat = runCatching { File(processDir, "stat").readText() }.getOrNull() ?: return null
        val parsed = parseStat(stat) ?: return null
        val uid = runCatching {
            File(processDir, "status").useLines { lines ->
                lines.first { it.startsWith("Uid:") }
                    .substringAfter(':')
                    .trim()
                    .split(WHITESPACE_REGEX, limit = 2)
                    .first()
                    .toLong()
            }
        }.getOrNull() ?: return null
        val commandLine = runCatching {
            File(processDir, "cmdline").inputStream().use { input ->
                val buffer = ByteArray(MAX_COMMAND_BYTES)
                val read = input.read(buffer)
                if (read <= 0) "" else buffer.decodeToString(0, read).trimEnd('\u0000')
                    .replace('\u0000', ' ')
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return WorkspaceProcessSnapshot(
            pid = pid,
            startTimeTicks = parsed.startTimeTicks,
            processGroupId = parsed.processGroupId,
            uid = uid,
            commandLine = commandLine,
            processName = parsed.processName,
        )
    }

    override fun signal(pidOrProcessGroup: Long, signal: Int): Boolean {
        if (pidOrProcessGroup == 0L || pidOrProcessGroup < Int.MIN_VALUE || pidOrProcessGroup > Int.MAX_VALUE) {
            return false
        }
        return runCatching {
            val osClass = Class.forName("android.system.Os")
            osClass.getMethod("kill", Integer.TYPE, Integer.TYPE)
                .invoke(null, pidOrProcessGroup.toInt(), signal)
        }.isSuccess
    }

    override fun syncDirectory(directory: File): Boolean = runCatching {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }.isSuccess

    override fun sleep(millis: Long) = Thread.sleep(millis)

    internal fun parseStat(stat: String): ProcStat? {
        // comm (field 2) may contain spaces and parentheses; every numeric field follows the last ')'.
        val commandStart = stat.indexOf('(')
        val commandEnd = stat.lastIndexOf(')')
        if (commandStart < 0 || commandEnd <= commandStart || commandEnd + 2 >= stat.length) return null
        val fields = stat.substring(commandEnd + 2).trim().split(WHITESPACE_REGEX)
        if (fields.size < START_TIME_INDEX + 1) return null
        return runCatching {
            ProcStat(
                processName = stat.substring(commandStart + 1, commandEnd),
                processGroupId = fields[PROCESS_GROUP_INDEX].toLong(),
                startTimeTicks = fields[START_TIME_INDEX].toLong(),
            )
        }.getOrNull()
    }

    internal data class ProcStat(
        val processName: String,
        val processGroupId: Long,
        val startTimeTicks: Long,
    )

    private companion object {
        // fields[0] is proc stat field 3 (state).
        private const val PROCESS_GROUP_INDEX = 2
        private const val START_TIME_INDEX = 19
        private const val MAX_COMMAND_BYTES = 8 * 1024
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
