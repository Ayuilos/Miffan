package me.ayuilos.miffan.data.skills.install

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillInstallTargetTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `canonical aliases do not make a new skill look like a conflict`() {
        val realParent = tempFolder.newFolder("real-parent").toPath()
        val realRoot = Files.createDirectory(realParent.resolve("skills"))
        val alias = tempFolder.root.toPath().resolve("root-alias")
        try {
            Files.createSymbolicLink(alias, realParent)
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val aliasedRoot = alias.resolve("skills")
        val canonicalTarget = realRoot.resolve("new-skill")

        assertEquals(
            canonicalTarget.toFile().canonicalFile.toPath(),
            resolveSkillInstallTarget(aliasedRoot, canonicalTarget),
        )
    }

    @Test
    fun `target outside canonical root is rejected`() {
        val root = tempFolder.newFolder("skills").toPath()
        val outside = tempFolder.root.toPath().resolve("outside-skill")

        assertNull(resolveSkillInstallTarget(root, outside))
    }
}
