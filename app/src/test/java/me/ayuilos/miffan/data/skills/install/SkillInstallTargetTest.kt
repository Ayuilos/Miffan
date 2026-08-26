package me.ayuilos.miffan.data.skills.install

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import me.rerere.workspace.WorkspaceConfig
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceResourceLimits

class SkillInstallTargetTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `workspace target publishes under miffan skills without overwrite`() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspace-targets"))
        manager.ensureWorkspace("root-1")
        val target = WorkspaceSkillInstallTarget(manager, "root-1")
        val files = mapOf(
            "SKILL.md" to "---\nname: safe-skill\ndescription: Safe\n---\n".toByteArray(),
            "references/readme.md" to "Reference".toByteArray(),
        )

        assertTrue(target.isAvailable())
        assertFalse(target.exists("safe-skill"))
        assertTrue(target.installNewAtomically("safe-skill", files))
        assertTrue(target.exists("safe-skill"))
        assertFalse(target.installNewAtomically("safe-skill", files))
        assertEquals(
            "Reference",
            manager.readText("root-1", ".miffan/skills/safe-skill/references/readme.md"),
        )
    }

    @Test
    fun `legacy migration accepts an old display-name directory without broadening remote installs`() {
        val manager = WorkspaceManager(tempFolder.newFolder("legacy-targets"))
        manager.ensureWorkspace("root-1")
        val target = WorkspaceSkillInstallTarget(manager, "root-1")
        val files = mapOf("SKILL.md" to "legacy".toByteArray())

        assertFalse(target.installNewAtomically("Display Name", files))
        assertTrue(target.installLegacySkillAtomically("Display Name", files))
        assertEquals(
            "legacy",
            manager.readText("root-1", ".miffan/skills/Display Name/SKILL.md"),
        )
    }

    @Test
    fun `workspace target rejects a symbolic link in destination chain`() {
        val manager = WorkspaceManager(tempFolder.newFolder("workspace-symlinks"))
        manager.ensureWorkspace("root-1")
        val outside = tempFolder.newFolder("outside-workspace").toPath()
        val miffan = manager.filesDir("root-1").toPath().resolve(".miffan")
        try {
            Files.createSymbolicLink(miffan, outside)
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val target = WorkspaceSkillInstallTarget(manager, "root-1")

        assertFalse(target.isAvailable())
        assertTrue(target.exists("safe-skill"))
        assertFalse(
            target.installNewAtomically(
                "safe-skill",
                mapOf("SKILL.md" to "safe".toByteArray()),
            )
        )
        assertFalse(Files.exists(outside.resolve("skills/safe-skill")))
    }

    @Test
    fun `workspace target honors workspace files quota`() {
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspace-quota"),
            config = WorkspaceConfig(
                resourceLimits = WorkspaceResourceLimits(
                    maxFilesBytes = 4,
                    maxWorkspaceBytes = 1024,
                    minFreeSpaceBytes = 0,
                )
            ),
        )
        manager.ensureWorkspace("root-1")
        val target = WorkspaceSkillInstallTarget(manager, "root-1")

        assertFalse(
            target.installNewAtomically(
                "safe-skill",
                mapOf("SKILL.md" to "12345".toByteArray()),
            )
        )
        assertFalse(target.exists("safe-skill"))
    }
}
