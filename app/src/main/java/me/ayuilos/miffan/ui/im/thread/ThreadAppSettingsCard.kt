package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
    val appearance = rememberAppearance()
    val plan = remember(tool.input, settings, appearance) { planAppSettingsChange(tool.inputAsJson(), settings, appearance = appearance) }
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
    val appearance = rememberAppearance()
    val lines = remember(tool.output, tool.input) {
        val output = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        appSettingsResultLines(output)
            ?: (planAppSettingsChange(tool.inputAsJson(), settings, appearance = appearance) as? AppSettingsPlan.Ok)?.lines
    }
    if (lines != null) AppSettingsChangeList(lines)
}

/** Light / dark as it is now, following changes from the appearance page. */
@Composable
private fun rememberAppearance(): me.ayuilos.miffan.data.appsettings.Appearance {
    val prefs = org.koin.compose.koinInject<me.ayuilos.miffan.data.appsettings.AppearanceStore>()
    val flow = remember(prefs) { prefs.changes() }
    return flow.collectAsState(prefs.current()).value
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
    is AppSettingsLine.SettingChanged -> settingLine(line)
    is AppSettingsLine.PartnerSetting -> partnerSettingLine(line)
    is AppSettingsLine.TtsVoice -> {
        val known = me.ayuilos.miffan.data.appsettings.TtsKeyService.of(line.type)?.voices?.firstOrNull { it.first == line.voice }
        // Some services name voices by opaque ids; their description reads better there.
        val shown = known?.second?.takeIf { line.voice.length >= 16 && line.voice.all(Char::isLetterOrDigit) } ?: line.voice
        ExtensionLine(R.string.im_settings_tag_voice, known == null,
            stringResource(R.string.im_settings_arrow, stringResource(R.string.im_settings_tts_voice), shown),
            if (known == null) stringResource(R.string.im_settings_voice_unknown, line.service) else stringResource(R.string.im_settings_from, line.service))
    }
    is AppSettingsLine.SearchSelected -> ExtensionLine(R.string.im_extension_tag_switch, false,
        stringResource(R.string.im_settings_search_in_use, line.service), null)
    is AppSettingsLine.DefaultModel -> ExtensionLine(R.string.im_settings_tag_default, false,
        stringResource(R.string.im_settings_arrow, stringResource(roleLabel(line.role)),
            line.model ?: stringResource(if (line.role == DefaultModelRole.IMAGE.id) R.string.im_settings_image_none else R.string.im_settings_follow_fast)),
        line.provider?.let { stringResource(R.string.im_settings_from, it) })
}

private fun roleLabel(role: String): Int = when (DefaultModelRole.of(role)) {
    DefaultModelRole.FAST -> R.string.setting_model_page_fast_model
    DefaultModelRole.TITLE -> R.string.setting_model_page_title_model
    DefaultModelRole.SUGGESTION -> R.string.setting_model_page_suggestion_model
    DefaultModelRole.TRANSLATE -> R.string.setting_model_page_translate_model
    DefaultModelRole.OCR -> R.string.setting_model_page_ocr_model
    DefaultModelRole.COMPRESS -> R.string.setting_model_page_compress_model
    DefaultModelRole.IMAGE -> R.string.im_settings_image_model
    DefaultModelRole.CHAT, null -> R.string.setting_model_page_chat_model
}

@Composable
private fun settingLine(line: AppSettingsLine.SettingChanged): ExtensionLine {
    @Composable
    fun onOff(value: String) = stringResource(if (value == "on") R.string.im_settings_on else R.string.im_settings_off)
    val (tag, name, value) = when (line.key) {
        "font_size" -> Triple(R.string.im_settings_tag_display, stringResource(R.string.im_settings_font_size), stringResource(when (line.value) {
            "small" -> R.string.im_settings_font_small
            "large" -> R.string.im_settings_font_large
            "larger" -> R.string.im_settings_font_larger
            else -> R.string.im_settings_font_default
        }))
        "latex" -> Triple(R.string.im_settings_tag_display, stringResource(R.string.im_settings_latex), onOff(line.value))
        "code_wrap" -> Triple(R.string.im_settings_tag_display, stringResource(R.string.im_settings_code_wrap), onOff(line.value))
        "code_collapse" -> Triple(R.string.im_settings_tag_display, stringResource(R.string.im_settings_code_collapse), onOff(line.value))
        "crop_before_send" -> Triple(R.string.im_settings_tag_display, stringResource(R.string.im_settings_crop), onOff(line.value))
        "theme" -> Triple(R.string.im_settings_tag_look, stringResource(R.string.im_appearance_color), line.label ?: themeName(line.value))
        "color_mode" -> Triple(R.string.im_settings_tag_look, stringResource(R.string.im_settings_color_mode), stringResource(when (line.value) {
            "light" -> R.string.im_appearance_mode_light
            "dark" -> R.string.im_appearance_mode_dark
            else -> R.string.im_appearance_mode_system
        }))
        "amoled" -> Triple(R.string.im_settings_tag_look, stringResource(R.string.im_appearance_amoled), onOff(line.value))
        "reply_done_notice" -> Triple(R.string.im_settings_tag_notify, stringResource(R.string.im_notify_reply), onOff(line.value))
        "live_notice" -> Triple(R.string.im_settings_tag_notify, stringResource(R.string.im_notify_progress), onOff(line.value))
        "owner_name" -> Triple(R.string.im_settings_tag_name, stringResource(R.string.im_settings_owner_name),
            line.value.ifEmpty { stringResource(R.string.im_settings_cleared) })
        "fast_thinking" -> Triple(R.string.im_settings_tag_default, stringResource(R.string.im_settings_fast_thinking), stringResource(when (line.value) {
            "quick" -> R.string.im_think_quick
            "more" -> R.string.im_think_more
            "deep" -> R.string.im_think_deep
            else -> R.string.im_think_auto
        }))
        "tts_service" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_tts_service), line.value)
        "asr_service" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_asr_service), line.value)
        "tts_speed" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_tts_speed),
            stringResource(R.string.im_settings_times, line.value))
        "tts_pitch" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_tts_pitch), line.value)
        "backup_reminder" -> Triple(R.string.im_settings_tag_backup, stringResource(R.string.im_backup_remind), onOff(line.value))
        "backup_interval" -> Triple(R.string.im_settings_tag_backup, stringResource(R.string.im_settings_backup_interval),
            line.value.toIntOrNull()?.let { pluralStringResource(R.plurals.im_backup_remind_desc, it, it) } ?: line.value)
        "auto_read" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_read_auto), onOff(line.value))
        "read_quoted" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_read_quoted), onOff(line.value))
        "skip_brackets" -> Triple(R.string.im_settings_tag_voice, stringResource(R.string.im_settings_skip_brackets), onOff(line.value))
        else -> Triple(R.string.im_extension_tag_update, line.key, line.value)
    }
    return ExtensionLine(tag, line.value == "off", stringResource(R.string.im_settings_arrow, name, value), null)
}

/** "How long it thinks → Quick", with the partner it is about underneath: the change may be for another partner. */
@Composable
private fun partnerSettingLine(line: AppSettingsLine.PartnerSetting): ExtensionLine {
    val partner = LocalSettings.current.assistants.firstOrNull { it.id.toString() == line.partnerId }
    val (name, value) = when (line.key) {
        "thinking" -> stringResource(R.string.im_think_title) to stringResource(when (line.value) {
            "quick" -> R.string.im_think_quick
            "more" -> R.string.im_think_more
            "deep" -> R.string.im_think_deep
            else -> R.string.im_think_auto
        })
        "reply_limit" -> stringResource(R.string.im_settings_reply_limit) to
            if (line.value == "none") stringResource(R.string.im_settings_no_limit) else stringResource(R.string.im_settings_tokens, line.value)
        "context_messages" -> stringResource(R.string.im_settings_context) to
            if (line.value == "all") stringResource(R.string.im_settings_all) else stringResource(R.string.im_settings_messages, line.value)
        "temperature" -> stringResource(R.string.im_settings_temperature) to
            if (line.value == "default") stringResource(R.string.im_settings_temperature_default) else line.value
        "memory" -> stringResource(R.string.im_p5_remember) to
            stringResource(if (line.value == "on") R.string.im_settings_on else R.string.im_settings_off)
        "shared_memory" -> stringResource(R.string.im_settings_shared_memory) to
            stringResource(if (line.value == "on") R.string.im_settings_on else R.string.im_settings_off)
        else -> line.key to line.value
    }
    return ExtensionLine(R.string.im_settings_tag_partner, line.value == "off", stringResource(R.string.im_settings_arrow, name, value),
        if (partner != null) partnerName(partner) else line.partner)
}

@Composable
private fun themeName(id: String): String = when (id) {
    me.ayuilos.miffan.data.appsettings.WALLPAPER_THEME -> stringResource(R.string.im_appearance_wallpaper)
    "sakura" -> stringResource(R.string.theme_name_sakura)
    "ocean" -> stringResource(R.string.theme_name_ocean)
    "spring" -> stringResource(R.string.theme_name_spring)
    "autumn" -> stringResource(R.string.theme_name_autumn)
    "black" -> stringResource(R.string.theme_name_black)
    "minimal" -> stringResource(R.string.theme_name_minimal)
    "claude" -> stringResource(R.string.theme_name_claude)
    else -> id
}
