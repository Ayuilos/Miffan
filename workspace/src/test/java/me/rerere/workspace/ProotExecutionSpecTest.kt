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
            root = "root",
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
    fun `interactive and AI shells share complete host environment`() {
        val loader = tmp.newFile("loader")
        val temp = tmp.newFolder("host-temp")
        val environment = ProotExecutionSpec.hostEnvironment(
            loader = loader,
            tempDir = temp,
            inheritedEnvironment = mapOf(
                "PATH" to "/system/bin",
                "PROOT_LOADER" to "/untrusted/loader",
                "TMPDIR" to "/untrusted/tmp",
            ),
        )

        assertEquals("/system/bin", environment["PATH"])
        assertEquals(loader.absolutePath, environment["PROOT_LOADER"])
        assertEquals(temp.absolutePath, environment["PROOT_TMP_DIR"])
        assertEquals(temp.absolutePath, environment["TMPDIR"])
    }

    @Test
    fun `interactive and AI shells share the same mount table`() {
        val linux = tmp.newFolder("shared-mount-linux")
        val files = tmp.newFolder("shared-mount-files")
        val exposed = tmp.newFolder("shared-mount-exposed")
        val hidden = tmp.newFolder("shared-mount-hidden")
        val mounts = listOf(
            WorkspaceBindMount(exposed, "/shared", exposeToShell = true),
            WorkspaceBindMount(hidden, "/hidden", exposeToShell = false),
        )
        val context = WorkspaceShellContext(
            root = "root",
            command = "true",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = tmp.newFolder("shared-mount-temp"),
            workingDir = files,
            timeoutMillis = 1_000,
            bindMounts = mounts,
        )

        val ai = ProotExecutionSpec.nonInteractiveCommand(context, tmp.newFile("shared-mount-proot"))
        val interactive = ProotExecutionSpec.interactiveArguments(
            root = "root",
            linuxDir = linux,
            filesDir = files,
            bindMounts = mounts,
        )

        assertEquals(bindArguments(ai), bindArguments(interactive))
        assertTrue(bindArguments(ai).contains("${exposed.absolutePath}:/shared"))
        assertFalse(bindArguments(ai).any { it.endsWith(":/hidden") })
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

    @Test
    fun `shell launch applies inherited file size limit`() {
        val linux = tmp.newFolder("limited-linux")
        val files = tmp.newFolder("limited-files")
        val temp = tmp.newFolder("limited-temp")
        val context = WorkspaceShellContext(
            root = "root",
            command = "true",
            cwd = "",
            filesDir = files,
            linuxDir = linux,
            tempDir = temp,
            workingDir = files,
            timeoutMillis = 1_000,
            maxFileSizeBytes = 2_048,
            maxCpuTimeSeconds = 60,
            maxVirtualMemoryBytes = 4_096,
            maxProcesses = 8,
        )

        val nonInteractive = ProotExecutionSpec.nonInteractiveCommand(context, tmp.newFile("proot"))
        val interactive = ProotExecutionSpec.interactiveArguments(
            root = "root",
            linuxDir = linux,
            filesDir = files,
            maxFileSizeBytes = 2_048,
            maxCpuTimeSeconds = 60,
            maxVirtualMemoryBytes = 4_096,
            maxProcesses = 8,
        )

        listOf(nonInteractive, interactive).forEach { arguments ->
            val script = arguments.first { it.contains("cap_limit()") }
            assertTrue(script.contains("cap_limit -f 2"))
            assertTrue(script.contains("cap_limit -t 60"))
            assertTrue(script.contains("cap_limit -v 4"))
            assertTrue(script.contains("cap_limit -u 8"))
            assertTrue(script.contains("exit 125"))
        }
    }

    private fun bindArguments(arguments: List<String>): List<String> =
        arguments.zipWithNext()
            .filter { (option, _) -> option == "-b" }
            .map { (_, value) -> value }
}
