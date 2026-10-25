package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.standingApprovalKey
import me.ayuilos.miffan.data.ai.tools.PartnerAbility
import me.ayuilos.miffan.data.ai.tools.REQUEST_ABILITY
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.message.tools.DefaultToolPreview
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRegistry
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardBlock
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.ImCardStatus
import me.ayuilos.miffan.ui.im.description
import me.ayuilos.miffan.ui.im.icon
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.im.rememberAbilityGate
import me.ayuilos.miffan.ui.im.title
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Tools

/**
 * Answers a card can give besides allow and decline, bound to the chat the call belongs to.
 * Null where the chat cannot offer them (partner chats settle approvals through their own service).
 */
internal class ToolCardActions(
    /** Allows this call and every later one of the same kind ("以后都直接做"). */
    val alwaysAllow: (toolCallId: String) -> Unit,
    /** Remembers the kind as always allowed without settling a call; used with [approveWithInput]. */
    val grantStanding: (key: String) -> Unit,
    /** Allows the call with arguments the owner corrected on the card (calendar time). */
    val approveWithInput: (toolCallId: String, input: JsonObject) -> Unit,
)

internal val LocalToolCardActions = staticCompositionLocalOf<ToolCardActions?> { null }

/** Professional mode asks for a reason before declining; easy mode declines and points to replying instead. */
internal val LocalToolCardDecline = staticCompositionLocalOf<((toolCallId: String) -> Unit)?> { null }

/** False while the call cannot be allowed yet (professional mode: its workspace target is still resolving). */
internal val LocalToolCardAllowEnabled = staticCompositionLocalOf { true }

/** Professional mode shows the technical details (target workspace, diffs) on the card itself. */
internal val LocalToolCardProfessional = staticCompositionLocalOf { false }

/**
 * The frame every approval card shares: what the partner wants to do as one sentence, the request in
 * readable fields, then the answer. Allowing turns the buttons into a short "carrying on" state until
 * the call leaves the pending state and the card shrinks into its record.
 *
 * [onAllow] replaces the plain allow (for example to send corrected arguments); it receives whether the
 * owner also chose "always".
 */
@Composable
internal fun ApprovalCardFrame(
    tool: UIMessagePart.Tool,
    title: String,
    onApproval: (String, Boolean) -> Unit,
    icon: ImageVector? = null,
    allowLabel: String = stringResource(R.string.im_p5_allow),
    allowEnabled: Boolean = true,
    footnote: String? = null,
    onAllow: ((always: Boolean) -> Unit)? = null,
    /** Allowing does something risky (an agent that skips every question); the button turns red. */
    allowDanger: Boolean = false,
    /** Runs before the allow takes effect and calls `proceed` once it may (e.g. after a system permission). */
    beforeAllow: ((proceed: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val actions = LocalToolCardActions.current
    val decline = LocalToolCardDecline.current
    val standingKey = remember(tool.toolName) { standingApprovalKey(tool.toolName) }
    // A turn another partner started always asks; offering "always" there would teach the wrong thing.
    val canAlways = standingKey != null && actions != null && !tool.isRelayApproval()
    var allowed by remember(tool.toolCallId) { mutableStateOf(false) }
    var always by remember(tool.toolCallId) { mutableStateOf(false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    ImActionCard(title = title, icon = icon,
        trailing = { TextButton(onClick = { details = true }) { Text(stringResource(R.string.im_approval_details)) } }) {
        content()
        footnote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RelayApprovalNotice(tool)
        if (canAlways && !allowed) StandingApprovalSwitch(always) { always = it }
        if (allowed) {
            ImCardStatus(stringResource(R.string.im_approval_allowed_continuing))
        } else {
            ImCardButtons {
                ImCardButton(allowLabel, enabled = allowEnabled && LocalToolCardAllowEnabled.current, danger = allowDanger, onClick = {
                    val proceed = {
                        allowed = true
                        val alwaysChosen = always && canAlways
                        when {
                            onAllow != null -> onAllow(alwaysChosen)
                            alwaysChosen -> actions!!.alwaysAllow(tool.toolCallId)
                            else -> onApproval(tool.toolCallId, true)
                        }
                    }
                    beforeAllow?.invoke(proceed) ?: proceed()
                })
                ImCardOutlinedButton(stringResource(R.string.im_p5_not_now), onClick = {
                    decline?.invoke(tool.toolCallId) ?: onApproval(tool.toolCallId, false)
                })
            }
            if (decline == null) Text(stringResource(R.string.im_approval_reply_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        DefaultToolPreview(ToolUIContext(tool, tool.inputAsJson(), null, false))
    }
}

@Composable
private fun StandingApprovalSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_approval_always), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.im_approval_always_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** One labelled value of a request, as the owner reads it ("来源 example.com"). */
@Composable
internal fun ApprovalFields(fields: List<Pair<String, @Composable () -> Unit>>) {
    if (fields.isEmpty()) return
    ImCardBlock {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            fields.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.widthIn(min = 40.dp, max = 72.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) { value() }
                }
            }
        }
    }
}

@Composable
internal fun FieldText(text: String, detail: String? = null, mono: Boolean = false, maxLines: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) FontFamily.Monospace else null)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A path as folders a person can follow: "/workspace/reports/q3.pdf" reads "reports › q3.pdf". */
internal fun readablePath(path: String): String =
    path.removePrefix("/workspace").trim('/').split('/').filter { it.isNotBlank() }.joinToString(" › ").ifBlank { path }

/** "mcp__notion__create_page" as the service and the step it names. */
internal fun mcpToolParts(toolName: String): Pair<String, String> {
    val name = toolName.removePrefix("mcp__")
    return name.substringBefore("__") to name.substringAfter("__")
}

/**
 * Any approval without a card of its own: what the partner wants to do in plain words, the request's
 * main values as fields, and the raw request one tap away.
 */
@Composable
internal fun ThreadGenericApprovalCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    val professional = LocalToolCardProfessional.current
    val context = remember(tool) { ToolUIContext(tool, tool.inputAsJson(), null, false) }
    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val path = toolArg(tool, "path")
    when (tool.toolName) {
        REQUEST_ABILITY -> ThreadAbilityRequestCard(tool, who, onApproval)
        REQUEST_WEB_SEARCH -> ApprovalCardFrame(tool, stringResource(R.string.im_approval_web_search, who), onApproval,
            icon = HugeIcons.Globe02, footnote = stringResource(R.string.im_p5_web_permission)) {
            Text(toolArg(tool, "reason") ?: stringResource(R.string.im_p5_web))
        }
        "workspace_fetch_url" -> {
            val url = toolArg(tool, "url").orEmpty()
            val uri = remember(url) { runCatching { java.net.URI(url) }.getOrNull() }
            ApprovalCardFrame(tool, stringResource(R.string.im_approval_fetch, who), onApproval, icon = HugeIcons.Download01,
                footnote = stringResource(R.string.im_approval_fetch_note)) {
                ApprovalFields(listOfNotNull(
                    stringResource(R.string.im_approval_field_source) to @Composable {
                        FieldText(uri?.host ?: url, detail = uri?.rawPath?.takeIf { it.isNotBlank() && it != "/" })
                    },
                    toolArg(tool, "destination_path")?.let { dest ->
                        stringResource(R.string.im_approval_field_save_to) to @Composable { FieldText(readablePath(dest)) }
                    },
                ))
                if (professional) renderer.Summary(context)
            }
        }
        "workspace_write_file", "workspace_edit_file", "workspace_read_file" -> {
            val (titleRes, icon) = when (tool.toolName) {
                "workspace_write_file" -> R.string.im_approval_write_file to HugeIcons.FileAdd
                "workspace_edit_file" -> R.string.im_approval_edit_file to HugeIcons.FileEdit
                else -> R.string.im_approval_read_file to HugeIcons.FileView
            }
            ApprovalCardFrame(tool, stringResource(titleRes, who), onApproval, icon = icon) {
                ApprovalFields(listOfNotNull(path?.let {
                    stringResource(R.string.im_approval_field_file) to @Composable { FieldText(readablePath(it)) }
                }))
                if (tool.toolName == "workspace_write_file") toolArg(tool, "text")?.let {
                    ChangeLongText(stringResource(R.string.im_approval_field_content), it)
                }
                if (professional || tool.toolName == "workspace_edit_file") renderer.Summary(context)
            }
        }
        WORKSPACE_SHELL_TOOL_NAME -> ApprovalCardFrame(tool, stringResource(R.string.im_approval_command, who), onApproval,
            icon = HugeIcons.ComputerTerminal01) {
            ApprovalFields(listOfNotNull(
                stringResource(R.string.im_approval_field_command) to @Composable {
                    FieldText(toolArg(tool, "command").orEmpty(), mono = true, maxLines = 6)
                },
                toolArg(tool, "cwd")?.let { cwd -> stringResource(R.string.im_approval_field_folder) to @Composable { FieldText(cwd, mono = true) } },
            ))
        }
        else -> if (tool.toolName.startsWith("mcp__")) {
            val (service, step) = mcpToolParts(tool.toolName)
            val args = remember(tool.input) {
                (tool.inputAsJson() as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it }
                }.take(MAX_ARG_FIELDS)
            }
            ApprovalCardFrame(tool, stringResource(R.string.im_approval_mcp, who, service, step), onApproval, icon = HugeIcons.McpServer) {
                ApprovalFields(args.map { (key, value) -> key to @Composable { FieldText(value) } })
            }
        } else ApprovalCardFrame(tool, stringResource(R.string.im_approval_tool, who, renderer.title(context)), onApproval,
            icon = HugeIcons.Tools, footnote = stringResource(R.string.im_p5_permission_info)) {
            if (renderer.hasSummary(context)) renderer.Summary(context)
        }
    }
}

private const val MAX_ARG_FIELDS = 4

/**
 * The partner asks to switch on one of its abilities. Allowing first gets what Android needs for it
 * (calendar permission, usage access); only then is the request approved and the ability turned on.
 */
@Composable
private fun ThreadAbilityRequestCard(tool: UIMessagePart.Tool, who: String, onApproval: (String, Boolean) -> Unit) {
    val ability = PartnerAbility.of(toolArg(tool, "ability"))
    if (ability == null) {
        ApprovalCardFrame(tool, stringResource(R.string.im_approval_tool, who, tool.toolName), onApproval, icon = HugeIcons.Tools)
        return
    }
    val gate = rememberAbilityGate()
    ApprovalCardFrame(tool, stringResource(R.string.im_ability_request_title, who, stringResource(ability.title)), onApproval,
        icon = ability.icon, allowLabel = stringResource(R.string.im_ability_turn_on), footnote = stringResource(ability.description),
        beforeAllow = { proceed -> gate.request(ability, proceed) }) {
        toolArg(tool, "reason")?.let { Text(it) }
        if (gate.waitingFor == ability) Text(
            stringResource(if (ability == PartnerAbility.CALENDAR) R.string.im_ability_waiting_calendar else R.string.im_ability_waiting_usage),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        )
    }
}
