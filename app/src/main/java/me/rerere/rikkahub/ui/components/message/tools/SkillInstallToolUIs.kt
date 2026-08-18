package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.skills.install.decodeSkillInstallPreviewSummaries

private fun ToolUIContext.payload(): JsonObject? = content as? JsonObject

private fun JsonObject.stringList(key: String): List<String> =
    (get(key) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

private fun JsonObject.errorLines(): List<String> = buildList {
    addAll(stringList("errors"))
    get("error")?.jsonPrimitive?.contentOrNull?.let(::add)
    get("unavailableReason")?.jsonPrimitive?.contentOrNull?.let(::add)
}.distinct()

internal fun pendingSkillInstallSummaries(arguments: JsonElement): List<String> {
    val previewId = (arguments as? JsonObject)
        ?.get("previewId")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: return emptyList()
    return decodeSkillInstallPreviewSummaries(previewId)
}

private fun ToolUIContext.summaryLines(): List<String> {
    if (tool.toolName == "skills_apply_install" && !tool.isExecuted) {
        return pendingSkillInstallSummaries(arguments)
    }
    val value = payload() ?: return emptyList()
    return value.stringList("summaries") + value.errorLines()
}

private object SkillsSearchToolUI : ToolUIRenderer {
    override val toolName: String = "skills_search"

    @Composable
    override fun title(context: ToolUIContext): String {
        val payload = context.payload()
        if ((payload?.get("available") as? JsonPrimitive)?.booleanOrNull == false) {
            return "Skill 搜索不可用"
        }
        val count = (payload?.get("results") as? JsonArray)?.size
        return if (count == null) "搜索 Skills" else "找到 $count 个 Skills"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.summaryLines().isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) = SkillInstallSummary(context.summaryLines())
}

private object SkillsPreviewInstallToolUI : ToolUIRenderer {
    override val toolName: String = "skills_preview_install"

    @Composable
    override fun title(context: ToolUIContext): String =
        if (context.payload()?.errorLines().orEmpty().isNotEmpty()) {
            "Skill 安装预览失败"
        } else {
            "预览 Skill 安装"
        }

    override fun hasSummary(context: ToolUIContext): Boolean = context.summaryLines().isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) = SkillInstallSummary(context.summaryLines())
}

private object SkillsApplyInstallToolUI : ToolUIRenderer {
    override val toolName: String = "skills_apply_install"

    @Composable
    override fun title(context: ToolUIContext): String {
        if (!context.tool.isExecuted) return "等待确认安装 Skill"
        val applied = (context.payload()?.get("applied") as? JsonPrimitive)?.booleanOrNull
        return if (applied == true) "已安装 Skill" else "Skill 安装失败"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.summaryLines().isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) = SkillInstallSummary(context.summaryLines())
}

@Composable
private fun SkillInstallSummary(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.take(5).forEach { line ->
            Text(
                text = "• $line",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (lines.size > 5) {
            Text(
                text = "另有 ${lines.size - 5} 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

internal val SkillInstallToolUIRenderers: List<ToolUIRenderer> = listOf(
    SkillsSearchToolUI,
    SkillsPreviewInstallToolUI,
    SkillsApplyInstallToolUI,
)
