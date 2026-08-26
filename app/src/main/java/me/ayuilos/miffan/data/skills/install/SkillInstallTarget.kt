package me.ayuilos.miffan.data.skills.install

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceScope

/** The local persistence boundary. Implementations must reject existing targets, including links. */
interface SkillInstallTarget {
    /** False when the backing directory is missing, replaced, or otherwise unsafe to publish into. */
    fun isAvailable(): Boolean = true

    fun exists(skillName: String): Boolean

    /** Writes all files as one new skill and never replaces an existing skill. */
    fun installNewAtomically(skillName: String, files: Map<String, ByteArray>): Boolean
}

/** Atomic, no-overwrite publisher for `/workspace/.miffan/skills`. */
class WorkspaceSkillInstallTarget(
    private val workspaceManager: WorkspaceManager,
    private val workspaceRoot: String,
    private val workspaceScope: WorkspaceScope = WorkspaceScope.LEGACY_WHOLE_WORKSPACE,
) : SkillInstallTarget {
    override fun isAvailable(): Boolean = runCatching {
        val files = safeFilesRoot() ?: return@runCatching false
        inspectSkillsRoot(files, create = false) != SkillsRootState.Unsafe
    }.getOrDefault(false)

    override fun exists(skillName: String): Boolean {
        if (!SAFE_SKILL_DIRECTORY_NAME.matches(skillName)) return true
        val filesRoot = safeFilesRoot() ?: return true
        return when (val state = inspectSkillsRoot(filesRoot, create = false)) {
            SkillsRootState.Missing -> false
            SkillsRootState.Unsafe -> true
            is SkillsRootState.Ready -> Files.exists(
                state.path.resolve(skillName),
                LinkOption.NOFOLLOW_LINKS,
            )
        }
    }

    override fun installNewAtomically(
        skillName: String,
        files: Map<String, ByteArray>,
    ): Boolean {
        if (!SAFE_SKILL_DIRECTORY_NAME.matches(skillName) || files.isEmpty()) return false
        return installFilesAtomically(skillName, files)
    }

    /** Publishes a validated app-private legacy Skill without broadening remote install names. */
    fun installLegacySkillAtomically(
        directoryName: String,
        files: Map<String, ByteArray>,
    ): Boolean {
        if (!isSafeLegacyDirectoryName(directoryName) || files.isEmpty()) return false
        return installFilesAtomically(directoryName, files)
    }

    private fun installFilesAtomically(
        directoryName: String,
        files: Map<String, ByteArray>,
    ): Boolean {
        val additionalBytes = files.values.fold(0L) { total, content ->
            runCatching { Math.addExact(total, content.size.toLong()) }
                .getOrElse { return false }
        }

        return runCatching {
            workspaceManager.withFilesWriteAccess(
                root = workspaceRoot,
                additionalBytes = additionalBytes,
                scope = workspaceScope,
            ) write@{ suppliedFilesRoot ->
                val filesRoot = suppliedFilesRoot.toPath().normalize()
                if (filesRoot != safeFilesRoot()) return@write false

                // Keep staged bytes outside the shell-visible files directory. The workspace
                // mutation lock prevents a running shell from racing the final destination.
                val workspace = workspaceManager.workspaceDir(workspaceRoot).toPath().normalize()
                val stagingRoot = workspace.resolve(".skill-install-staging")
                if (stagingRoot.parent != workspace) return@write false
                if (Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS) &&
                    !isDirectoryNoFollow(stagingRoot)
                ) {
                    return@write false
                }
                Files.createDirectories(stagingRoot)
                if (!isDirectoryNoFollow(stagingRoot)) return@write false

                val staging = stagingRoot.resolve("$directoryName.${UUID.randomUUID()}.tmp")
                try {
                    Files.createDirectory(staging)
                    for ((relativePath, content) in files) {
                        val output = staging.resolve(relativePath).normalize()
                        if (output == staging || !output.startsWith(staging)) return@write false
                        val parent = output.parent ?: return@write false
                        Files.createDirectories(parent)
                        if (!isDirectoryNoFollow(parent)) return@write false
                        Files.newOutputStream(
                            output,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS,
                        ).use { it.write(content) }
                    }
                    if (!Files.isRegularFile(staging.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
                        return@write false
                    }

                    val skillsRoot = when (val state = inspectSkillsRoot(filesRoot, create = true)) {
                        SkillsRootState.Missing,
                        SkillsRootState.Unsafe,
                        -> return@write false
                        is SkillsRootState.Ready -> state.path
                    }
                    val target = skillsRoot.resolve(directoryName)
                    if (target.parent != skillsRoot ||
                        Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        return@write false
                    }

                    // The target was rechecked while the workspace mutation lock was held. Publish
                    // the complete directory with one same-filesystem atomic rename.
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    true
                } finally {
                    if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                        staging.toFile().deleteRecursively()
                    }
                }
            }
        }.getOrDefault(false)
    }

    private fun safeFilesRoot(): Path? = runCatching {
        val workspace = workspaceManager.workspaceDir(workspaceRoot).toPath().normalize()
        val files = workspaceManager.ensureScope(workspaceRoot, workspaceScope)
            .files.toPath().normalize()
        files.takeIf {
            files.startsWith(workspace) && files != workspace &&
                isDirectoryNoFollow(workspace) && isDirectoryNoFollow(files)
        }
    }.getOrNull()

    private fun inspectSkillsRoot(filesRoot: Path, create: Boolean): SkillsRootState {
        var current = filesRoot
        for (segment in WORKSPACE_SKILLS_PATH_SEGMENTS) {
            val child = current.resolve(segment)
            if (child.parent != current) return SkillsRootState.Unsafe
            if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                if (!isDirectoryNoFollow(child)) return SkillsRootState.Unsafe
            } else if (!create) {
                return SkillsRootState.Missing
            } else {
                try {
                    Files.createDirectory(child)
                } catch (_: Exception) {
                    if (!isDirectoryNoFollow(child)) return SkillsRootState.Unsafe
                }
            }
            current = child
        }
        return SkillsRootState.Ready(current)
    }

    private sealed interface SkillsRootState {
        data class Ready(val path: Path) : SkillsRootState
        data object Missing : SkillsRootState
        data object Unsafe : SkillsRootState
    }

    private companion object {
        val SAFE_SKILL_DIRECTORY_NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val WORKSPACE_SKILLS_PATH_SEGMENTS = listOf(".miffan", "skills")

        fun isSafeLegacyDirectoryName(value: String): Boolean =
            value.length in 1..100 && value != "." && value != ".." &&
                value.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}

private fun isDirectoryNoFollow(path: Path): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
