package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.appsettings.AppSettingsLine
import me.ayuilos.miffan.data.appsettings.AppSettingsPlan
import me.ayuilos.miffan.data.appsettings.DefaultModelRole
import me.ayuilos.miffan.data.appsettings.appSettingsResultLines
import me.ayuilos.miffan.data.appsettings.planAppSettingsChange
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain02

/**
 * The partner proposes changes to the AI services. The card lists what the app would do with the settings as they
 * are now; if they changed so that the proposal no longer applies, it can only be declined.
 */
@Composable
internal fun ThreadAppSettingsCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val settings = LocalSettings.current
    val plan = remember(tool.input, settings) { planAppSettingsChange(tool.inputAsJson(), settings) }
    val lines = (plan as? AppSettingsPlan.Ok)?.lines
    ApprovalCardFrame(tool, stringResource(R.string.im_change_app_settings), onApproval, icon = HugeIcons.AiBrain02,
        allowEnabled = lines != null,
        footnote = when {
            lines == null -> null
            lines.any { it is AppSettingsLine.ProviderAddress && it.clearsKey } -> stringResource(R.string.im_settings_footnote_key)
            else -> stringResource(R.string.im_settings_footnote)
        }) {
        toolArg(tool, "reason")?.let { Text(it) }
        if (lines != null) AppSettingsChangeList(lines)
        else Text(stringResource(R.string.im_settings_outdated), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
    }
}

/** In the record: what was applied (from the result), or, for a declined call, what was proposed. */
@Composable
internal fun AppSettingsChangeContent(tool: UIMessagePart.Tool) {
    val settings = LocalSettings.current
    val lines = remember(tool.output, tool.input) {
        val output = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        appSettingsResultLines(output)
            ?: (planAppSettingsChange(tool.inputAsJson(), settings) as? AppSettingsPlan.Ok)?.lines
    }
    if (lines != null) AppSettingsChangeList(lines)
}

@Composable
internal fun AppSettingsChangeList(lines: List<AppSettingsLine>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                ExtensionLineRow(appSettingsLine(line))
            }
        }
    }
}

@Composable
private fun appSettingsLine(line: AppSettingsLine): ExtensionLine = when (line) {
    is AppSettingsLine.ProviderEnabled -> ExtensionLine(
        if (line.enabled) R.string.im_extension_tag_on else R.string.im_extension_tag_off, !line.enabled, line.provider, null)
    is AppSettingsLine.ProviderRenamed -> ExtensionLine(R.string.im_settings_tag_rename, false,
        stringResource(R.string.im_settings_arrow, line.from, line.to), null)
    is AppSettingsLine.ProviderAddress -> ExtensionLine(R.string.im_settings_tag_address, line.clearsKey, line.provider,
        stringResource(if (line.clearsKey) R.string.im_settings_address_clears_key else R.string.im_settings_address, line.url ?: line.origin))
    is AppSettingsLine.ModelsAdded -> ExtensionLine(R.string.im_settings_tag_add, false,
        pluralStringResource(R.plurals.im_settings_models, line.models.size, line.provider, line.models.size), line.models.joinToString("、"))
    is AppSettingsLine.ModelsRemoved -> ExtensionLine(R.string.im_settings_tag_remove, true,
        pluralStringResource(R.plurals.im_settings_models, line.models.size, line.provider, line.models.size), line.models.joinToString("、"))
    is AppSettingsLine.ModelUpdated -> ExtensionLine(R.string.im_extension_tag_update, false, line.model, buildList {
        line.name?.let { add(stringResource(R.string.im_settings_shown_as, it)) }
        line.tools?.let { add(stringResource(if (it) R.string.im_settings_tools_on else R.string.im_settings_tools_off)) }
        line.reasoning?.let { add(stringResource(if (it) R.string.im_settings_reasoning_on else R.string.im_settings_reasoning_off)) }
        line.imageInput?.let { add(stringResource(if (it) R.string.im_settings_image_on else R.string.im_settings_image_off)) }
        add(line.provider)
    }.joinToString(" · "))
    is AppSettingsLine.PartnerModel -> {
        val partner = LocalSettings.current.assistants.firstOrNull { it.id.toString() == line.partnerId }
        val name = if (partner != null) partnerName(partner) else line.partner
        ExtensionLine(R.string.im_settings_tag_model, false,
            stringResource(R.string.im_settings_arrow, name, line.model ?: stringResource(R.string.im_settings_follow_default)),
            line.provider?.let { stringResource(R.string.im_settings_from, it) })
    }
    is AppSettingsLine.SearchSelected -> ExtensionLine(R.string.im_extension_tag_switch, false,
        stringResource(R.string.im_settings_search_in_use, line.service), null)
    is AppSettingsLine.DefaultModel -> ExtensionLine(R.string.im_settings_tag_default, false,
        stringResource(R.string.im_settings_arrow, stringResource(roleLabel(line.role)),
            line.model ?: stringResource(R.string.im_settings_follow_fast)),
        line.provider?.let { stringResource(R.string.im_settings_from, it) })
}

private fun roleLabel(role: String): Int = when (DefaultModelRole.of(role)) {
    DefaultModelRole.FAST -> R.string.setting_model_page_fast_model
    DefaultModelRole.TITLE -> R.string.setting_model_page_title_model
    DefaultModelRole.SUGGESTION -> R.string.setting_model_page_suggestion_model
    DefaultModelRole.TRANSLATE -> R.string.setting_model_page_translate_model
    DefaultModelRole.OCR -> R.string.setting_model_page_ocr_model
    DefaultModelRole.COMPRESS -> R.string.setting_model_page_compress_model
    DefaultModelRole.CHAT, null -> R.string.setting_model_page_chat_model
}
