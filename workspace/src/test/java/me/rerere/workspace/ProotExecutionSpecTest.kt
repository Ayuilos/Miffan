package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotExecutionSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `custom bind mounts cannot shadow or expose assistant scope roots`() {
        val source = tmp.newFolder("scope-shadow-source")
        listOf("/workspace/sibling", "/root/config", "/tmp/cache", "/var", "/").forEach { target ->
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceBindMount(source, target, exposeToShell = false)
            }
        }
    }

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
    fun `scoped shell mounts only selected files home and temp directories`() {
        val linux = tmp.newFolder("scope-linux")
        val scopeParent = tmp.newFolder("scopes")
        val selected = java.io.File(scopeParent, "assistant-a").apply { mkdirs() }
        val sibling = java.io.File(scopeParent, "assistant-b").apply { mkdirs() }
        val files = java.io.File(selected, "files").apply { mkdirs() }
        val home = java.io.File(selected, "home").apply { mkdirs() }
        val guestTemp = java.io.File(selected, "tmp").apply { mkdirs() }
        val varTemp = java.io.File(selected, "var-tmp").apply { mkdirs() }

        val binds = bindArguments(
            ProotExecutionSpec.baseArguments(
                root = "root",
                linuxDir = linux,
                filesDir = files,
                cwd = "/workspace",
                bindMounts = emptyList(),
                homeDir = home,
                guestTempDir = guestTemp,
                guestVarTempDir = varTemp,
            )
        )

        assertTrue(binds.contains("${files.absolutePath}:/workspace"))
        assertTrue(binds.contains("${home.absolutePath}:/root"))
        assertTrue(binds.contains("${guestTemp.absolutePath}:/tmp"))
        assertTrue(binds.contains("${varTemp.absolutePath}:/var/tmp"))
        assertFalse(binds.any { it.contains(scopeParent.absolutePath + ":") })
        assertFalse(binds.any { it.contains(sibling.absolutePath) })
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
        val home = tmp.newFolder("shared-mount-home")
        val guestTemp = tmp.newFolder("shared-mount-guest-temp")
        val varTemp = tmp.newFolder("shared-mount-var-temp")
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
            homeDir = home,
            guestTempDir = guestTemp,
            guestVarTempDir = varTemp,
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
            homeDir = home,
            guestTempDir = guestTemp,
            guestVarTempDir = varTemp,
        )

        assertEquals(bindArguments(ai), bindArguments(interactive))
        assertTrue(bindArguments(ai).contains("${exposed.absolutePath}:/shared"))
        assertTrue(bindArguments(ai).contains("${home.absolutePath}:/root"))
        assertTrue(bindArguments(ai).contains("${guestTemp.absolutePath}:/tmp"))
        assertTrue(bindArguments(ai).contains("${varTemp.absolutePath}:/var/tmp"))
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
