package me.rerere.workspace

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceNativeProcessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `launcher rejects NUL before entering JNI`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceNativeProcess.start(
                command = listOf("/system/bin/sh", "bad\u0000argument"),
                environment = emptyMap(),
                workingDirectory = tmp.root,
            )
        }
    }

    @Test
    fun `launcher rejects invalid environment names before entering JNI`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceNativeProcess.start(
                command = listOf("/system/bin/sh"),
                environment = mapOf("BAD=NAME" to "value"),
                workingDirectory = tmp.root,
            )
        }
    }
}
