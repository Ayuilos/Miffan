package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotExecutionSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `global read-only mappings are omitted from PRoot arguments`() {
        val linux = tmp.newFolder("linux")
        val files = tmp.newFolder("files")
        val upload = tmp.newFolder("upload")
        val args = ProotExecutionSpec.baseArguments(
            linuxDir = linux,
            filesDir = files,
            cwd = "/workspace",
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = upload,
                    target = "/upload",
                    exposeToShell = false,
                    writableByTools = false,
                )
            ),
        )

        assertTrue(args.contains("${files.absolutePath}:/workspace"))
        assertFalse(args.any { it.endsWith(":/upload") })
    }

    @Test
    fun `interactive and AI shells share base environment`() {
        val interactive = ProotExecutionSpec.guestEnvironment(interactive = true)
        val nonInteractive = ProotExecutionSpec.guestEnvironment(interactive = false)

        assertEquals(interactive, nonInteractive.filterNot { it == "-l" })
        assertTrue(interactive.contains("HOME=/root"))
        assertTrue(interactive.contains("SHELL=/bin/bash"))
        assertEquals("/workspace/project", ProotExecutionSpec.guestCwd("project"))
    }

    @Test
    fun `guest cwd rejects aliases and absolute paths`() {
        listOf("/workspace", "a/../b", "a//b", "a/").forEach { cwd ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                ProotExecutionSpec.guestCwd(cwd)
            }
        }
        assertEquals("/workspace/a/b", ProotExecutionSpec.guestCwd("a/b"))
    }
}
