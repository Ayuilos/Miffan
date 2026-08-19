package me.ayuilos.miffan.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.extensions.decodeExtensionPreviewSummaries

private fun ToolUIContext.previewPayload(): JsonObject? {
    return content as? JsonObject
}

private fun ToolUIContext.changeSummaries(): List<String> =
    (previewPayload()?.get("summaries") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

private fun ToolUIContext.errors(): List<String> =
    (previewPayload()?.get("errors") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

private fun ToolUIContext.pendingApplySummaries(): List<String> {
    val previewId = (arguments as? JsonObject)
        ?.get("previewId")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: return emptyList()
    return decodeExtensionPreviewSummaries(previewId)
}

private fun ToolUIContext.summaryLines(): List<String> = when {
    tool.toolName == "extensions_apply_changes" && !tool.isExecuted -> pendingApplySummaries()
    else -> changeSummaries() + errors()
}

private object ExtensionsCatalogToolUI : ToolUIRenderer {
    override val toolName: String = "extensions_catalog"

    @Composable
    override fun title(context: ToolUIContext): String = "查看扩展配置"
}

private object ExtensionsPreviewChangesToolUI : ToolUIRenderer {
    override val toolName: String = "extensions_preview_changes"

    @Composable
    override fun title(context: ToolUIContext): String {
        val valid = (context.previewPayload()?.get("valid") as? JsonPrimitive)?.booleanOrNull
        return if (valid == false) "扩展配置变更校验失败" else "预览扩展配置变更"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.summaryLines().isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        ChangeSummary(context.summaryLines())
    }
}

private object ExtensionsApplyChangesToolUI : ToolUIRenderer {
    override val toolName: String = "extensions_apply_changes"

    @Composable
    override fun title(context: ToolUIContext): String {
        if (!context.tool.isExecuted) return "等待确认扩展配置变更"
        val applied = (context.previewPayload()?.get("applied") as? JsonPrimitive)?.booleanOrNull
        return if (applied == true) "已应用扩展配置变更" else "扩展配置变更应用失败"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.summaryLines().isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        ChangeSummary(context.summaryLines())
    }
}

@Composable
private fun ChangeSummary(summaries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        summaries.take(5).forEach { summary ->
            Text(
                text = "• $summary",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (summaries.size > 5) {
            Text(
                text = "另有 ${summaries.size - 5} 项变更",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

internal val ExtensionManagementToolUIRenderers: List<ToolUIRenderer> = listOf(
    ExtensionsCatalogToolUI,
    ExtensionsPreviewChangesToolUI,
    ExtensionsApplyChangesToolUI,
)
