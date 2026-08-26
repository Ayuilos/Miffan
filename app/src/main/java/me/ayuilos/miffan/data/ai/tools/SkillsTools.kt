package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.ayuilos.miffan.data.files.SkillFrontmatterParser
import me.ayuilos.miffan.data.files.SkillMetadata
import me.ayuilos.miffan.data.files.SkillPaths
import me.ayuilos.miffan.data.files.SkillScope

fun createSkillTools(
    allSkills: List<SkillMetadata>,
    builtInSkills: List<BuiltInSkillDefinition> = emptyList(),
    workspaceReady: Boolean = false,
): List<Tool> {
    require(builtInSkills.distinctBy { it.name }.size == builtInSkills.size) {
        "Built-in skill names must be unique"
    }

    val builtInsByName = builtInSkills.associateBy { it.name }
    // A bundled definition is trusted application content and must not be shadowed by a
    // user-controlled skill directory with the same frontmatter name.
    val availableUserSkills = allSkills
        .asSequence()
        .filter { skill ->
            skill.scope == SkillScope.WORKSPACE &&
                (!skill.requiresWorkspace || workspaceReady) &&
                skill.name !in builtInsByName
        }
        .distinctBy { it.name }
        .toList()
    if (builtInSkills.isEmpty() && availableUserSkills.isEmpty()) return emptyList()

    return listOf(
        Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
            """.trimIndent(),
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("**Skills**")
                    appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                    appendLine("<available_skills>")
                    builtInSkills.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name.escapeXmlText()}</name>")
                        appendLine("    <description>${skill.description.escapeXmlText()}</description>")
                        appendLine("  </skill>")
                    }
                    availableUserSkills.forEach { skill ->
                        appendLine("  <skill>")
                        appendLine("    <name>${skill.name.escapeXmlText()}</name>")
                        appendLine("    <description>${skill.description.escapeXmlText()}</description>")
                        appendLine("  </skill>")
                    }
                    append("</available_skills>")
                    appendLine()
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = builtInsByName[name]?.let { skill ->
                    if (path.isNullOrBlank()) {
                        skill.body
                    } else {
                        require(isSafeBundledSkillPath(path)) {
                            "Path '$path' is outside the bundled skill"
                        }
                        skill.bundledFiles[path]
                            ?: error("File '$path' not found in skill '$name'")
                    }
                } ?: run {
                    val skill = availableUserSkills.firstOrNull { skill -> skill.name == name }
                        ?: error(
                            "Skill '$name' is not available. Available skills: " +
                                (builtInSkills.map { it.name } + availableUserSkills.map { it.name })
                                    .joinToString()
                        )
                    if (path.isNullOrBlank()) {
                        require(skill.skillFile.exists()) { "Skill '$name' not found" }
                        SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                    } else {
                        val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                            ?: error("Path '$path' is outside the skill directory")
                        require(target.exists()) { "File '$path' not found in skill '$name'" }
                        target.readText()
                    }
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}

class BuiltInSkillDefinition(
    val name: String,
    val description: String,
    val body: String,
    bundledFiles: Map<String, String> = emptyMap(),
) {
    val bundledFiles: Map<String, String> = bundledFiles.toMap()

    init {
        require(name.isNotBlank()) { "Built-in skill name must not be blank" }
        require(description.isNotBlank()) { "Built-in skill description must not be blank" }
        require(bundledFiles.keys.all(::isSafeBundledSkillPath)) {
            "Bundled skill file paths must be non-empty relative paths without traversal"
        }
    }
}

private fun isSafeBundledSkillPath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || '\\' in path) return false
    return path.split('/').none { it.isBlank() || it == "." || it == ".." }
}

/** Encode untrusted metadata before embedding it in the XML-like system-prompt catalog. */
private fun String.escapeXmlText(): String = buildString(length) {
    this@escapeXmlText.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
