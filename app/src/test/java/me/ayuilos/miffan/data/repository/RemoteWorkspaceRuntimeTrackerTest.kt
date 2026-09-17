package me.ayuilos.miffan.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkspaceRuntimeTrackerTest {
    private var time = 100L
    private val tracker = RemoteWorkspaceRuntimeTracker { time++ }

    @Test fun idleDoesNotClaimAnOpenConnectionAfterSuccessfulOperation() {
        tracker.begin("host", "workspace")
        assertEquals(RemoteConnectionActivity.CONNECTING, tracker.hostStates.value.getValue("host").activity)
        assertEquals(RemoteConnectionActivity.CONNECTING, tracker.workspaceStates.value.getValue("workspace").activity)

        tracker.connected("host", "workspace")
        assertEquals(RemoteConnectionActivity.OPERATING, tracker.hostStates.value.getValue("host").activity)
        tracker.operation("workspace", RemoteOperationOutcome.SUCCESS)
        tracker.completed("host", "workspace")

        val host = tracker.hostStates.value.getValue("host")
        val workspace = tracker.workspaceStates.value.getValue("workspace")
        assertEquals(RemoteConnectionActivity.IDLE, host.activity)
        assertTrue(host.lastConnection!!.success)
        assertEquals(RemoteConnectionActivity.IDLE, workspace.activity)
        assertTrue(workspace.lastDirectoryCheck!!.success)
        assertEquals(RemoteOperationOutcome.SUCCESS, workspace.lastOperation!!.outcome)
    }

    @Test fun inaccessibleDirectoryDoesNotMarkHostOfflineOrOtherWorkspaceBroken() {
        tracker.begin("host", "one")
        tracker.connected("host", "one")
        tracker.completed("host", "one")
        tracker.begin("host", "two")
        tracker.directoryFailed("host", "two", "Permission denied")

        assertTrue(tracker.hostStates.value.getValue("host").lastConnection!!.success)
        assertTrue(tracker.workspaceStates.value.getValue("one").lastDirectoryCheck!!.success)
        val failure = tracker.workspaceStates.value.getValue("two").lastDirectoryCheck!!
        assertFalse(failure.success)
        assertEquals("Permission denied", failure.reason)
        assertEquals(RemoteConnectionActivity.IDLE, tracker.hostStates.value.getValue("host").activity)
    }

    @Test fun missingCredentialIsConfigurationNotConnectionFailure() {
        tracker.begin("host", "workspace")
        tracker.configurationFailed(
            "host", "workspace", RemoteConfigurationState.CREDENTIAL_MISSING, "Key unavailable",
        )

        val host = tracker.hostStates.value.getValue("host")
        assertEquals(RemoteConfigurationState.CREDENTIAL_MISSING, host.configuration)
        assertEquals("Key unavailable", host.configurationReason)
        assertNull(host.lastConnection)
        assertEquals(RemoteConnectionActivity.IDLE, host.activity)
    }

    @Test fun commandExitAndUnknownResultDoNotChangeSuccessfulConnectionFact() {
        tracker.begin("host", "workspace")
        tracker.connected("host", "workspace")
        tracker.operation("workspace", RemoteOperationOutcome.COMMAND_FAILED, "Exit 7", 7)
        tracker.completed("host", "workspace")
        assertEquals(7, tracker.workspaceStates.value.getValue("workspace").lastOperation!!.exitCode)
        assertTrue(tracker.hostStates.value.getValue("host").lastConnection!!.success)

        tracker.begin("host", "workspace")
        tracker.connected("host", "workspace")
        tracker.operation("workspace", RemoteOperationOutcome.OUTCOME_UNKNOWN, "Timed out")
        tracker.completed("host", "workspace")
        assertEquals(RemoteOperationOutcome.OUTCOME_UNKNOWN,
            tracker.workspaceStates.value.getValue("workspace").lastOperation!!.outcome)
        assertTrue(tracker.hostStates.value.getValue("host").lastConnection!!.success)
    }

    @Test fun overlappingCallsKeepActivityUntilLastCallCompletes() {
        tracker.begin("host", "one")
        tracker.connected("host", "one")
        tracker.begin("host", "two")
        tracker.connected("host", "two")
        tracker.completed("host", "one")
        assertEquals(RemoteConnectionActivity.OPERATING, tracker.hostStates.value.getValue("host").activity)
        tracker.completed("host", "two")
        assertEquals(RemoteConnectionActivity.IDLE, tracker.hostStates.value.getValue("host").activity)
    }

    @Test fun oldInFlightCallCannotPublishIntoChangedHostRevision() {
        tracker.begin("host", "workspace", revision = "old")
        tracker.revisionChanged("host", "new", listOf("workspace"))
        tracker.configuration("host", RemoteConfigurationState.HOST_KEY_UNTRUSTED, revision = "new")
        tracker.connected("host", "workspace", revision = "old")
        tracker.operation("workspace", RemoteOperationOutcome.SUCCESS, revision = "old")
        tracker.completed("host", "workspace", revision = "old")

        val host = tracker.hostStates.value.getValue("host")
        assertEquals(RemoteConfigurationState.HOST_KEY_UNTRUSTED, host.configuration)
        assertEquals(RemoteConnectionActivity.IDLE, host.activity)
        assertNull(host.lastConnection)
        assertNull(tracker.workspaceStates.value.getValue("workspace").lastOperation)

        tracker.begin("host", "workspace", revision = "new")
        tracker.connected("host", "workspace", revision = "new")
        tracker.completed("host", "workspace", revision = "new")
        assertTrue(tracker.hostStates.value.getValue("host").lastConnection!!.success)
    }
}
