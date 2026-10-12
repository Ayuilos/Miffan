package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.data.model.characterMotionProfileOrDefault
import me.ayuilos.miffan.data.model.miffanAppearanceOrDefault
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.partner.partnerChatTitle
import me.ayuilos.miffan.data.partner.partnerDisplayName
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal const val CREATE_PARTNER = "create_partner"
internal const val CREATE_GROUP_CHAT = "create_group_chat"
internal const val INVITE_TO_GROUP_CHAT = "invite_to_group_chat"
internal const val ORGANIZE_CHAT_LIST = "organize_chat_list"
internal const val VIEW_CHAT_LIST = "view_chat_list"

/** Changes a partner proposes to the app itself; each has its own card that shows the result in plain words. */
internal val AGENT_CHANGE_TOOLS = setOf(UPDATE_MY_PROFILE, CREATE_PARTNER, CREATE_GROUP_CHAT, INVITE_TO_GROUP_CHAT, ORGANIZE_CHAT_LIST)

@StringRes
internal fun agentChangeTitle(toolName: String): Int = when (toolName) {
    UPDATE_MY_PROFILE -> R.string.im_profile_title
    CREATE_PARTNER -> R.string.im_change_create_partner
    CREATE_GROUP_CHAT -> R.string.im_change_create_group_chat
    INVITE_TO_GROUP_CHAT -> R.string.im_change_invite
    else -> R.string.im_change_organize
}

internal fun toolArg(tool: UIMessagePart.Tool, key: String): String? =
    ((tool.inputAsJson() as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun toolIds(tool: UIMessagePart.Tool, key: String): List<Uuid> =
    ((tool.inputAsJson() as? JsonObject)?.get(key) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() } }

/** The avatar a profile tool asks for, drawn the way the app will store it; [previous] resolves "previous". */
internal fun requestedAvatar(tool: UIMessagePart.Tool, current: Avatar?, previous: Avatar? = null): Avatar? {
    val motion = current?.characterMotionProfileOrDefault() ?: MiffanMotionProfile.CURIOUS
    toolArg(tool, "avatar_emoji")?.trim()?.let { return Avatar.Emoji(it) }
    return when (toolArg(tool, "avatar_preset")) {
        "whale_girl" -> Avatar.WhaleGirl(motion)
        "miffan" -> {
            val base = current?.takeIf { it is Avatar.Miffan }?.miffanAppearanceOrDefault() ?: MiffanAppearance()
            Avatar.Miffan(
                appearance = base.copy(
                    kind = MiffanKind.entries.firstOrNull { it.name.lowercase() == toolArg(tool, "miffan_kind") } ?: base.kind,
                    palette = MiffanPalette.entries.firstOrNull { it.name.lowercase() == toolArg(tool, "miffan_palette") } ?: base.palette,
                ),
                motionProfile = motion,
            )
        }
        "previous" -> previous
        else -> null
    }
}

/** The frame every change card shares: what it is, the partner's reason, the change itself, and the answer. */
@Composable
internal fun AgentChangeCard(
    tool: UIMessagePart.Tool,
    onApproval: (String, Boolean) -> Unit,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(agentChangeTitle(tool.toolName)), style = MaterialTheme.typography.titleMedium)
            toolArg(tool, "reason")?.let { Text(it) }
            content()
            footer?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, true) }) {
                    Text(stringResource(R.string.im_p5_allow))
                }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !answered, onClick = { answered = true; onApproval(tool.toolCallId, false) }) {
                    Text(stringResource(R.string.im_p5_not_now))
                }
            }
        }
    }
}

@Composable
internal fun ChangeField(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun ChangeArrow() {
    Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A long text the owner may want to read in full before agreeing; tap to expand. */
@Composable
internal fun ChangeLongText(label: String, text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A row of partners, each as its avatar over its name. */
@Composable
private fun PartnerFaces(partners: List<Assistant?>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        partners.forEach { partner ->
            Column(Modifier.widthIn(max = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val name = partnerDisplayName(partner)
                partner?.let { AssistantAvatar(name, it.avatar, Modifier.size(40.dp)) }
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun ThreadCreatePartnerCard(tool: UIMessagePart.Tool, creator: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val name = toolArg(tool, "name")?.trim().orEmpty()
    val avatar = requestedAvatar(tool, null) ?: Assistant().avatar
    val footer = buildString {
        creator?.let { append(stringResource(R.string.im_change_create_partner_contact, partnerName(it))).append(' ') }
        append(stringResource(R.string.im_change_create_partner_defaults))
    }
    AgentChangeCard(tool, onApproval, footer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistantAvatar(name, avatar, Modifier.size(48.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        toolArg(tool, "system_prompt")?.let { ChangeLongText(stringResource(R.string.im_change_partner_prompt), it) }
    }
}

@Composable
internal fun ThreadGroupChatCard(tool: UIMessagePart.Tool, caller: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val settings = LocalSettings.current
    val invited = toolIds(tool, "member_ids").distinct().map { id -> settings.assistants.firstOrNull { it.id == id } }
    if (tool.toolName == CREATE_GROUP_CHAT) {
        AgentChangeCard(tool, onApproval) {
            ChangeField(stringResource(R.string.im_change_group_title)) {
                Text(toolArg(tool, "title").orEmpty(), fontWeight = FontWeight.Medium)
            }
            Text(stringResource(R.string.im_change_group_members), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PartnerFaces(listOfNotNull(caller) + invited.filter { it?.id != caller?.id })
        }
    } else {
        val chats: PartnerChatService = koinInject()
        val chatId = toolArg(tool, "chat_id")?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val chat by remember(chatId) { chatId?.let { chats.observeChat(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
        AgentChangeCard(tool, onApproval) {
            chat?.let { ChangeField(stringResource(R.string.im_change_group_title)) { Text(partnerChatTitle(it, settings), fontWeight = FontWeight.Medium) } }
            Text(stringResource(R.string.im_change_invitees), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PartnerFaces(invited)
        }
    }
}

/** Each requested step of tidying the Chats list, in the order it will run, with chats and folders by name. */
@Composable
internal fun ThreadOrganizeCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val settings = LocalSettings.current
    val chats: PartnerChatService = koinInject()
    val summaries by chats.observeSummaries().collectAsState(emptyList())
    val operations = ((tool.inputAsJson() as? JsonObject)?.get("operations") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val refs = operations.mapNotNull { op -> op.str("ref")?.let { ref -> ref to op.str("name").orEmpty() } }.toMap()
    fun chatName(id: String?): String {
        val uuid = id?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return id.orEmpty()
        settings.assistants.firstOrNull { it.id == uuid }?.let { return partnerDisplayName(it) }
        return summaries.firstOrNull { it.chat.id == uuid }?.let { partnerChatTitle(it.chat, settings) } ?: id
    }
    fun folderName(op: JsonObject): String? = op.str("ref")?.let { refs[it] }
        ?: op.str("folder_id")?.let { id -> settings.chatOrganization.groups.firstOrNull { it.id.toString() == id }?.name ?: id }
    val lines = operations.map { op ->
        when (op.str("type")) {
            "pin" -> stringResource(R.string.im_change_op_pin, chatName(op.str("chat_id")))
            "unpin" -> stringResource(R.string.im_change_op_unpin, chatName(op.str("chat_id")))
            "create_folder" -> stringResource(R.string.im_change_op_create_folder, op.str("name").orEmpty())
            "rename_folder" -> stringResource(R.string.im_change_op_rename_folder, folderName(op.copyWithoutRef()).orEmpty(), op.str("name").orEmpty())
            "move_to_folder" -> folderName(op)?.let { stringResource(R.string.im_change_op_move, chatName(op.str("chat_id")), it) }
                ?: stringResource(R.string.im_change_op_ungroup, chatName(op.str("chat_id")))
            "move_folder" -> {
                val offset = (op["offset"] as? JsonPrimitive)?.intOrNull ?: 0
                stringResource(if (offset < 0) R.string.im_change_op_folder_up else R.string.im_change_op_folder_down,
                    folderName(op).orEmpty(), kotlin.math.abs(offset))
            }
            "delete_empty_folder" -> stringResource(R.string.im_change_op_delete_folder, folderName(op).orEmpty())
            else -> op.str("type").orEmpty()
        }
    }
    AgentChangeCard(tool, onApproval) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEachIndexed { index, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** Renaming names an existing folder; a stray "ref" must not be mistaken for a new one. */
private fun JsonObject.copyWithoutRef(): JsonObject = JsonObject(this - "ref")
