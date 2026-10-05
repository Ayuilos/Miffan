package me.ayuilos.miffan.ui.components.message.tools

import me.ayuilos.miffan.R
import androidx.compose.ui.res.stringResource
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
    override fun title(context: ToolUIContext): String = stringResource(R.string.extension_management_view_config)
}

private object ExtensionsPreviewChangesToolUI : ToolUIRenderer {
    override val toolName: String = "extensions_preview_changes"

    @Composable
    override fun title(context: ToolUIContext): String {
        val valid = (context.previewPayload()?.get("valid") as? JsonPrimitive)?.booleanOrNull
        return if (valid == false) stringResource(R.string.extension_management_preview_invalid) else stringResource(R.string.extension_management_preview)
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
        if (!context.tool.isExecuted) return stringResource(R.string.extension_management_apply_waiting)
        val applied = (context.previewPayload()?.get("applied") as? JsonPrimitive)?.booleanOrNull
        return if (applied == true) stringResource(R.string.extension_management_applied) else stringResource(R.string.extension_management_apply_failed)
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
                text = stringResource(R.string.extension_management_more_changes, summaries.size - 5),
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
