package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceProcessSupervisorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `live owner record is retained and stale owner process group is recovered`() {
        val stateDir = tmp.newFolder("process-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot()
        val supervisor = WorkspaceProcessSupervisor(stateDir, system)

        supervisor.register(
            root = "root",
            pid = TARGET_PID,
            isolatedProcessGroup = true,
            commandIdentity = PROOT_IDENTITY,
        )

        val liveReport = supervisor.recoverStaleProcesses()
        assertEquals(1, liveReport.activeOwnerRecords)
        assertEquals(1, stateDir.jsonRecords().size)
        assertTrue(system.signals.isEmpty())

        system.processes.remove(OWNER_PID)
        val recovered = supervisor.recoverStaleProcesses()

        assertEquals(1, recovered.terminatedProcesses)
        assertEquals(0, recovered.remainingProcesses)
        assertEquals(listOf(-TARGET_PID to SIGKILL), system.signals)
        assertTrue(stateDir.jsonRecords().isEmpty())
    }

    @Test
    fun `pid reuse is discarded without signaling unrelated process`() {
        val stateDir = tmp.newFolder("pid-reuse-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot()
        val supervisor = WorkspaceProcessSupervisor(stateDir, system)
        supervisor.register("root", TARGET_PID, true, PROOT_IDENTITY)

        system.processes.remove(OWNER_PID)
        system.processes[TARGET_PID] = targetSnapshot().copy(
            startTimeTicks = TARGET_START + 1,
            commandLine = "/system/bin/unrelated",
        )
        val report = supervisor.recoverStaleProcesses()

        assertEquals(1, report.discardedRecords)
        assertTrue(system.signals.isEmpty())
        assertTrue(stateDir.jsonRecords().isEmpty())
        assertTrue(system.processes.containsKey(TARGET_PID))
    }

    @Test
    fun `termination treats a reused pid as gone without signaling it`() {
        val stateDir = tmp.newFolder("terminate-pid-reuse-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot()
        val registration = requireNotNull(
            WorkspaceProcessSupervisor(stateDir, system)
                .register("root", TARGET_PID, true, PROOT_IDENTITY)
        )
        system.processes[TARGET_PID] = targetSnapshot().copy(startTimeTicks = TARGET_START + 1)

        assertTrue(registration.terminate(graceful = false))
        assertTrue(system.signals.isEmpty())
    }

    @Test
    fun `registration requires command identity and an isolated group leader`() {
        val stateDir = tmp.newFolder("invalid-registration-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        val supervisor = WorkspaceProcessSupervisor(stateDir, system)

        system.processes[TARGET_PID] = targetSnapshot().copy(processGroupId = TARGET_PID + 1)
        assertThrows(IllegalArgumentException::class.java) {
            supervisor.register("root", TARGET_PID, true, PROOT_IDENTITY)
        }

        system.processes[TARGET_PID] = targetSnapshot().copy(commandLine = "/system/bin/sh")
        assertThrows(IllegalArgumentException::class.java) {
            supervisor.register("root", TARGET_PID, false, PROOT_IDENTITY)
        }
        assertTrue(stateDir.jsonRecords().isEmpty())
    }

    @Test
    fun `registration waits for setsid transition before publishing record`() {
        val stateDir = tmp.newFolder("delayed-setsid-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot().copy(processGroupId = OWNER_PID)
        system.onSleep = {
            system.processes[TARGET_PID] = targetSnapshot()
        }

        val registration = WorkspaceProcessSupervisor(stateDir, system)
            .register("root", TARGET_PID, true, PROOT_IDENTITY)

        assertTrue(system.sleepCalls > 0)
        assertEquals(1, stateDir.jsonRecords().size)
        system.processes.remove(TARGET_PID)
        requireNotNull(registration).close()
        assertTrue(stateDir.jsonRecords().isEmpty())
    }

    @Test
    fun `failed recovery signal keeps record for a later retry`() {
        val stateDir = tmp.newFolder("failed-recovery-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot()
        WorkspaceProcessSupervisor(stateDir, system)
            .register("root", TARGET_PID, true, PROOT_IDENTITY)
        system.processes.remove(OWNER_PID)
        system.removeOnSignal = false

        val report = WorkspaceProcessSupervisor(stateDir, system).recoverStaleProcesses()

        assertEquals(1, report.remainingProcesses)
        assertEquals(listOf(-TARGET_PID to SIGKILL), system.signals)
        assertEquals(1, stateDir.jsonRecords().size)
    }

    @Test
    fun `close retries durable record removal after asynchronous process exit`() {
        val stateDir = tmp.newFolder("normal-exit-state")
        val system = FakeWorkspaceProcessSystem()
        system.processes[OWNER_PID] = ownerSnapshot()
        system.processes[TARGET_PID] = targetSnapshot()
        val registration = requireNotNull(
            WorkspaceProcessSupervisor(stateDir, system)
                .register("root", TARGET_PID, true, PROOT_IDENTITY)
        )

        assertEquals(1, stateDir.jsonRecords().size)
        registration.close()
        assertEquals(1, stateDir.jsonRecords().size)
        system.processes.remove(TARGET_PID)
        registration.close()

        assertTrue(stateDir.jsonRecords().isEmpty())
    }

    @Test
    fun `corrupt and temporary records are removed without signals`() {
        val stateDir = tmp.newFolder("corrupt-state")
        stateDir.resolve("bad.json").writeText("not json")
        stateDir.resolve("partial.json.tmp").writeText("partial")
        val system = FakeWorkspaceProcessSystem()

        val report = WorkspaceProcessSupervisor(stateDir, system).recoverStaleProcesses()

        assertEquals(1, report.discardedRecords)
        assertTrue(stateDir.listFiles().orEmpty().isEmpty())
        assertTrue(system.signals.isEmpty())
    }

    @Test
    fun `proc stat parser handles spaces and parentheses in command name`() {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[2] = TARGET_PID.toString()
        fields[19] = TARGET_START.toString()

        val parsed = ProcfsWorkspaceProcessSystem().parseStat(
            "$TARGET_PID (worker (one)) ${fields.joinToString(" ")}"
        )

        requireNotNull(parsed)
        assertEquals(TARGET_PID, parsed.processGroupId)
        assertEquals(TARGET_START, parsed.startTimeTicks)
    }

    private fun ownerSnapshot() = WorkspaceProcessSnapshot(
        pid = OWNER_PID,
        startTimeTicks = OWNER_START,
        processGroupId = OWNER_PID,
        uid = UID,
        commandLine = "me.rerere.rikkahub",
    )

    private fun targetSnapshot() = WorkspaceProcessSnapshot(
        pid = TARGET_PID,
        startTimeTicks = TARGET_START,
        processGroupId = TARGET_PID,
        uid = UID,
        commandLine = "$PROOT_IDENTITY --root-id",
    )

    private fun java.io.File.jsonRecords() =
        listFiles().orEmpty().filter { it.extension == "json" }

    private companion object {
        private const val OWNER_PID = 10L
        private const val OWNER_START = 100L
        private const val TARGET_PID = 20L
        private const val TARGET_START = 200L
        private const val UID = 10_123L
        private const val PROOT_IDENTITY = "/data/app/libproot_exec.so"
        private const val SIGKILL = 9
    }
}

private class FakeWorkspaceProcessSystem : WorkspaceProcessSystem {
    val processes = mutableMapOf<Long, WorkspaceProcessSnapshot>()
    val signals = mutableListOf<Pair<Long, Int>>()
    var removeOnSignal = true
    var sleepCalls = 0
    var onSleep: (() -> Unit)? = null

    override fun currentPid(): Long = 10L

    override fun bootId(): String = "test-boot"

    override fun snapshot(pid: Long): WorkspaceProcessSnapshot? = processes[pid]

    override fun signal(pidOrProcessGroup: Long, signal: Int): Boolean {
        signals += pidOrProcessGroup to signal
        if (removeOnSignal) processes.remove(kotlin.math.abs(pidOrProcessGroup))
        return true
    }

    override fun syncDirectory(directory: java.io.File): Boolean = true

    override fun sleep(millis: Long) {
        sleepCalls++
        onSleep?.invoke()
        Thread.sleep(millis)
    }
}
