package me.rerere.rikkahub.data.skills.install

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import me.rerere.rikkahub.data.files.SkillManager

/** The local persistence boundary. Implementations must reject existing targets, including links. */
interface SkillInstallTarget {
    fun exists(skillName: String): Boolean

    /** Writes all files as one new skill and never replaces an existing skill. */
    fun installNewAtomically(skillName: String, files: Map<String, ByteArray>): Boolean
}

/**
 * Atomic, no-overwrite adapter for the app-private skills directory.
 *
 * All content is written into a fresh staging directory. Moving that directory publishes the skill
 * in one filesystem operation. Existing files and symbolic links are treated as conflicts.
 */
class SkillManagerInstallTarget(
    private val skillManager: SkillManager,
) : SkillInstallTarget {
    override fun exists(skillName: String): Boolean {
        val target = resolveTarget(skillName) ?: return true
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    }

    override fun installNewAtomically(
        skillName: String,
        files: Map<String, ByteArray>,
    ): Boolean {
        val root = canonicalPath(skillManager.getSkillsDir().toPath()) ?: return false
        if (Files.isSymbolicLink(root)) return false
        val target = resolveTarget(skillName) ?: return false
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false

        // `/skills` can be bind-mounted into an assistant workspace. Keep unapproved/staged bytes
        // in a private sibling directory so workspace tools cannot observe or alter them before
        // the final no-replace move publishes the approved package.
        val stagingRoot = root.parent?.resolve(".skill-install-staging") ?: return false
        if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(stagingRoot) || !Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS))
        ) {
            return false
        }
        val staging = stagingRoot.resolve("$skillName.${UUID.randomUUID()}.tmp")
        return try {
            Files.createDirectories(stagingRoot)
            if (Files.isSymbolicLink(stagingRoot)) return false
            Files.createDirectory(staging)
            for ((relativePath, content) in files) {
                val output = staging.resolve(relativePath).normalize()
                if (output == staging || !output.startsWith(staging)) return false
                val parent = output.parent ?: return false
                Files.createDirectories(parent)
                if (Files.isSymbolicLink(parent)) return false
                Files.newOutputStream(
                    output,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { it.write(content) }
            }

            if (!Files.isRegularFile(staging.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false

            // No REPLACE_EXISTING: an existing file, directory, or link is always a conflict.
            Files.move(staging, target)
            true
        } catch (_: Exception) {
            false
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                staging.toFile().deleteRecursively()
            }
        }
    }

    private fun resolveTarget(skillName: String): java.nio.file.Path? {
        val root = canonicalPath(skillManager.getSkillsDir().toPath()) ?: return null
        val targetFile = skillManager.getSkillDir(skillName) ?: return null
        return resolveSkillInstallTarget(root, targetFile.toPath())
    }
}

/**
 * Compare canonical paths on both sides. Android may expose the same app-private directory through
 * aliases such as `/data/user/0/...` and `/data/data/...`; mixing an absolute root with a canonical
 * child makes every valid new Skill look like an unsafe target.
 */
internal fun resolveSkillInstallTarget(canonicalRoot: Path, targetPath: Path): Path? {
    val root = canonicalPath(canonicalRoot) ?: return null
    val target = canonicalPath(targetPath) ?: return null
    return target.takeIf { it.parent == root && it.startsWith(root) }
}

private fun canonicalPath(path: Path): Path? = runCatching {
    path.toFile().canonicalFile.toPath().normalize()
}.getOrNull()
