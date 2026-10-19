package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.extensions.ExtensionChange
import me.ayuilos.miffan.data.extensions.ExtensionChangePreview
import me.ayuilos.miffan.data.extensions.ExtensionResourceType
import me.ayuilos.miffan.data.extensions.decodeExtensionPreviewSummaries
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Puzzle

internal const val EXTENSIONS_APPLY = "extensions_apply_changes"
private const val EXTENSIONS_PREVIEW = "extensions_preview_changes"

/** The other parts of the reply a card sits in; a card may read an earlier step's result from them. */
internal val LocalToolCardSiblings = staticCompositionLocalOf<List<UIMessagePart>> { emptyList() }

private val previewJson = Json { ignoreUnknownKeys = true }

/** One change as a line the owner can read: what happens, to what, and for whom. */
private data class ExtensionLine(@StringRes val tag: Int, val removes: Boolean, val text: String, val detail: String?)

/**
 * The partner asks to apply an extension preview. The app's own preview of the same id lists the
 * changes; without it (another reply, or professional mode) the preview's own summaries stand in.
 */
@Composable
internal fun ThreadExtensionCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    ApprovalCardFrame(tool, stringResource(R.string.im_extension_title, who), onApproval, icon = HugeIcons.Puzzle,
        allowLabel = stringResource(R.string.im_extension_apply),
        footnote = stringResource(R.string.im_extension_footnote)) {
        ExtensionChangeList(tool)
    }
}

/** The changes an extensions_apply_changes call carries, one tagged line each; shared by the card and its record. */
@Composable
internal fun ExtensionChangeList(tool: UIMessagePart.Tool) {
    val settings = LocalSettings.current
    val siblings = LocalToolCardSiblings.current
    val previewId = toolArg(tool, "previewId")
    val preview = remember(previewId, siblings) { previewId?.let { findPreview(siblings, it) } }
    val fallback = remember(previewId) { previewId?.let(::decodeExtensionPreviewSummaries).orEmpty() }
    val lines = preview?.changes?.map { extensionLine(it, settings) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (lines != null) lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                ExtensionLineRow(line)
            } else fallback.forEach { summary ->
                Text(summary, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun findPreview(parts: List<UIMessagePart>, previewId: String): ExtensionChangePreview? = parts.asReversed()
    .filterIsInstance<UIMessagePart.Tool>()
    .filter { it.toolName == EXTENSIONS_PREVIEW && it.isExecuted }
    .firstNotNullOfOrNull { tool ->
        tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { part ->
            runCatching { previewJson.decodeFromString<ExtensionChangePreview>(part.text) }.getOrNull()
        }?.takeIf { it.previewId == previewId && it.valid }
    }

@Composable
private fun extensionLine(change: ExtensionChange, settings: Settings): ExtensionLine {
    fun partner(id: String) = settings.assistants.firstOrNull { it.id.toString() == id }?.let { it.name.ifBlank { null } }
    @Composable
    fun forPartner(id: String) = partner(id)?.let { stringResource(R.string.im_extension_for, it) }
    return when (change) {
        is ExtensionChange.UpsertQuickMessage -> ExtensionLine(
            if (change.create) R.string.im_extension_tag_create else R.string.im_extension_tag_update, false,
            stringResource(R.string.im_extension_quick_message, change.title), change.content.lineSequence().firstOrNull())
        is ExtensionChange.UpsertModeInjection -> ExtensionLine(
            if (change.create) R.string.im_extension_tag_create else R.string.im_extension_tag_update, false,
            stringResource(R.string.im_extension_mode, change.name), change.content.lineSequence().firstOrNull())
        is ExtensionChange.SetResourceBinding -> {
            val name = when (change.resourceType) {
                ExtensionResourceType.QUICK_MESSAGE -> settings.quickMessages.firstOrNull { it.id.toString() == change.resourceId }?.title
                ExtensionResourceType.MODE_INJECTION -> settings.modeInjections.firstOrNull { it.id.toString() == change.resourceId }?.name
                ExtensionResourceType.LOREBOOK -> settings.lorebooks.firstOrNull { it.id.toString() == change.resourceId }?.name
                ExtensionResourceType.MCP -> settings.mcpServers.firstOrNull { it.id.toString() == change.resourceId }?.commonOptions?.name
            } ?: change.resourceId
            val text = when (change.resourceType) {
                ExtensionResourceType.QUICK_MESSAGE -> R.string.im_extension_quick_message
                ExtensionResourceType.MODE_INJECTION -> R.string.im_extension_mode
                ExtensionResourceType.LOREBOOK -> R.string.im_extension_lorebook
                ExtensionResourceType.MCP -> R.string.im_extension_mcp
            }
            ExtensionLine(if (change.enabled) R.string.im_extension_tag_on else R.string.im_extension_tag_off, !change.enabled,
                stringResource(text, name), forPartner(change.assistantId))
        }
        is ExtensionChange.SetWorkspace -> {
            val workspace = change.workspaceId
            ExtensionLine(if (workspace == null) R.string.im_extension_tag_off else R.string.im_extension_tag_switch, workspace == null,
                stringResource(if (workspace == null) R.string.im_extension_no_workspace else R.string.im_extension_workspace),
                forPartner(change.assistantId))
        }
        is ExtensionChange.SetLocalTool -> ExtensionLine(
            if (change.enabled) R.string.im_extension_tag_on else R.string.im_extension_tag_off, !change.enabled,
            localToolLabel(change.localToolId), forPartner(change.assistantId))
        is ExtensionChange.SetExternalWebSearch -> ExtensionLine(
            if (change.enabled) R.string.im_extension_tag_on else R.string.im_extension_tag_off, !change.enabled,
            stringResource(R.string.im_p5_web), forPartner(change.assistantId))
    }
}

@Composable
private fun localToolLabel(id: String): String = when (id) {
    "javascript_engine" -> stringResource(R.string.assistant_page_local_tools_javascript_engine_title)
    "time_info" -> stringResource(R.string.assistant_page_local_tools_time_info_title)
    "clipboard" -> stringResource(R.string.assistant_page_local_tools_clipboard_title)
    "tts" -> stringResource(R.string.assistant_page_local_tools_tts_title)
    "ask_user" -> stringResource(R.string.assistant_page_local_tools_ask_user_title)
    "screen_time" -> stringResource(R.string.assistant_page_local_tools_screen_time_title)
    "calendar" -> stringResource(R.string.assistant_page_local_tools_calendar_title)
    "bug_report" -> stringResource(R.string.assistant_page_local_tools_bug_report_title)
    else -> id
}

@Composable
private fun ExtensionLineRow(line: ExtensionLine) {
    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        val (container, content) = if (line.removes) MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 1.dp)) {
            Text(stringResource(line.tag), Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            line.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
