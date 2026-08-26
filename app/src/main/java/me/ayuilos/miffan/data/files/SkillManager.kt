package me.ayuilos.miffan.data.files

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.skills.install.WorkspaceSkillInstallTarget
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceScope

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val workspaceManager: WorkspaceManager,
) {
    companion object {
        private const val TAG = "SkillManager"
        const val WORKSPACE_SKILLS_PATH = ".miffan/skills"
        private const val MAX_LEGACY_MIGRATION_FILES = 512
        private const val MAX_LEGACY_MIGRATION_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_LEGACY_MIGRATION_TOTAL_BYTES = 64L * 1024 * 1024
    }

    /** Read-only compatibility source used to migrate pre-workspace Skill installations. */
    fun listLegacySkills(): List<SkillMetadata> {
        return discoverSkills(
            skillsDir = legacySkillsDir(),
            scope = SkillScope.GLOBAL,
        )
    }

    /**
     * Discover project-owned Skills from the persistent files area of one workspace.
     *
     * The directory is intentionally not created while listing. Merely opening a workspace must
     * not add Miffan metadata to an otherwise empty project.
     */
    fun listWorkspaceSkills(
        workspaceId: String,
        workspaceRoot: String,
        scopeId: String? = null,
    ): List<SkillMetadata> {
        val scope = WorkspaceScope.fromNullableId(scopeId)
        val skillsDir = workspaceManager.filesDir(workspaceRoot, scope).resolve(WORKSPACE_SKILLS_PATH)
        return discoverSkills(
            skillsDir = skillsDir,
            scope = SkillScope.WORKSPACE,
            workspaceId = workspaceId,
            workspaceScopeId = scopeId,
        )
    }

    /**
     * One-way compatibility migration for the old global binding model.
     *
     * Successfully migrated names are removed from [Assistant.enabledSkills]. The legacy source is
     * retained as a read-only recovery copy and is never loaded into a conversation again.
     */
    suspend fun migrateLegacySkillsToWorkspace(
        assistant: Assistant,
        workspace: WorkspaceEntity,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (assistant.enabledSkills.isEmpty() ||
            assistant.workspaceId?.toString() != workspace.id
        ) {
            return@withContext emptySet()
        }

        val legacyByName = listLegacySkills().associateBy { it.name }
        val scopeId = assistant.workspaceScopeId?.toString()
        val workspaceNames = listWorkspaceSkills(workspace.id, workspace.root, scopeId)
            .mapTo(hashSetOf()) { it.name }
        val target = WorkspaceSkillInstallTarget(
            workspaceManager,
            workspace.root,
            WorkspaceScope.fromNullableId(scopeId),
        )
        if (!target.isAvailable()) return@withContext emptySet()

        val completed = buildSet {
            for (name in assistant.enabledSkills) {
                val legacy = legacyByName[name]
                if (legacy == null || name in workspaceNames) {
                    add(name)
                    continue
                }
                val files = readLegacySkillFiles(legacy.skillDir) ?: continue
                if (
                    target.installLegacySkillAtomically(
                        directoryName = legacy.skillDir.name,
                        files = files,
                    )
                ) {
                    add(name)
                }
            }
        }
        if (completed.isNotEmpty()) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { current ->
                        if (current.id == assistant.id &&
                            current.workspaceId == assistant.workspaceId &&
                            current.workspaceScopeId == assistant.workspaceScopeId
                        ) {
                            current.copy(enabledSkills = current.enabledSkills - completed)
                        } else {
                            current
                        }
                    }
                )
            }
        }
        completed
    }

    private fun legacySkillsDir(): File = context.filesDir.resolve(FileFolders.SKILLS)

    private fun readLegacySkillFiles(skillDir: File): Map<String, ByteArray>? {
        val root = skillDir.toPath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return null
        }
        val files = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root) continue
                if (Files.isSymbolicLink(path)) return null
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
                if (files.size >= MAX_LEGACY_MIGRATION_FILES) return null
                val size = Files.size(path)
                if (size > MAX_LEGACY_MIGRATION_FILE_BYTES) return null
                totalBytes = runCatching { Math.addExact(totalBytes, size) }.getOrNull() ?: return null
                if (totalBytes > MAX_LEGACY_MIGRATION_TOTAL_BYTES) return null
                val relativePath = root.relativize(path).joinToString("/") { it.toString() }
                if (SkillPaths.resolveSkillFile(skillDir, relativePath)?.toPath()?.normalize() != path) {
                    return null
                }
                files[relativePath] = Files.readAllBytes(path)
            }
        }
        return files.takeIf { "SKILL.md" in it }
    }

}

internal fun discoverSkills(
    skillsDir: File,
    scope: SkillScope,
    workspaceId: String? = null,
    workspaceScopeId: String? = null,
): List<SkillMetadata> {
    if (!Files.isDirectory(skillsDir.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return skillsDir.listFiles()
        ?.asSequence()
        ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        ?.sortedBy { it.name }
        ?.mapNotNull { dir ->
            val skillFile = dir.resolve("SKILL.md")
            if (!Files.isRegularFile(skillFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@mapNotNull null
            }
            parseSkillFile(
                skillFile = skillFile,
                skillDir = dir,
                scope = scope,
                workspaceId = workspaceId,
                workspaceScopeId = workspaceScopeId,
            )
        }
        ?.toList()
        ?: emptyList()
}

private fun parseSkillFile(
    skillFile: File,
    skillDir: File,
    scope: SkillScope,
    workspaceId: String? = null,
    workspaceScopeId: String? = null,
): SkillMetadata? {
    return runCatching {
        val content = skillFile.readText()
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
        val allowedTools = frontmatter["allowed-tools"]
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()
        SkillMetadata(
            name = name,
            description = description,
            compatibility = frontmatter["compatibility"],
            allowedTools = allowedTools,
            skillDir = skillDir,
            scope = scope,
            workspaceId = workspaceId,
            workspaceScopeId = workspaceScopeId,
            requiresWorkspace = frontmatter.boolean("requires-workspace") == true ||
                allowedTools.any(::isWorkspaceToolDeclaration),
        )
    }.getOrElse {
        Log.w("SkillManager", "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
        null
    }
}

private fun isWorkspaceToolDeclaration(tool: String): Boolean {
    val normalized = tool.substringBefore('(').trim().lowercase()
    return normalized in WORKSPACE_TOOL_DECLARATIONS || normalized.startsWith("workspace_")
}

private val WORKSPACE_TOOL_DECLARATIONS = setOf(
    "bash",
    "edit",
    "glob",
    "grep",
    "read",
    "shell",
    "write",
)

enum class SkillScope {
    GLOBAL,
    WORKSPACE,
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
    val scope: SkillScope = SkillScope.GLOBAL,
    val workspaceId: String? = null,
    val workspaceScopeId: String? = null,
    val requiresWorkspace: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
