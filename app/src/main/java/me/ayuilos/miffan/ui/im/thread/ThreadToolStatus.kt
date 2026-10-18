package me.ayuilos.miffan.ui.im.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.revision.ToolCallChangeState
import org.koin.compose.koinInject
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import androidx.compose.ui.text.font.FontWeight
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME
import me.ayuilos.miffan.ui.components.message.ChatMessageToolStep
import me.ayuilos.miffan.ui.components.message.LocalTerminalConversationId
import me.ayuilos.miffan.ui.components.message.approvalDecisionText
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.components.message.tools.ToolUIRegistry
import me.ayuilos.miffan.ui.components.message.ThinkingStep
import me.ayuilos.miffan.ui.components.ui.ChainOfThought
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01

internal const val ASK_USER = "ask_user"
internal const val REQUEST_WEB_SEARCH = "request_web_search"
internal const val UPDATE_MY_PROFILE = "update_my_profile"

/**
 * What a streaming reply is doing right now, or null once its own text (or a prompt card) is the feedback.
 * Easy mode keeps only a plain step record ([ThreadStepRecord]); process details belong to professional mode.
 */
@StringRes
internal fun threadLiveStatus(parts: List<UIMessagePart>, streaming: Boolean): Int? {
    if (!streaming) return null
    for (part in parts.asReversed()) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) return null
            is UIMessagePart.Reasoning -> return R.string.im_thread_status_thinking
            // A partner message has its own line in the reply, which already says it is asking; a drawing has its placeholder.
            is UIMessagePart.Tool -> return if (part.approvalState is ToolApprovalState.Pending || part.isPartnerMessage() ||
                part.isImageGeneration()) null else toolStatus(part.toolName)
            is UIMessagePart.ServerTool -> return toolStatus(part.toolName)
            is UIMessagePart.Image, is UIMessagePart.Audio, is UIMessagePart.Video -> return null
            else -> Unit
        }
    }
    return R.string.im_thread_status_thinking
}

private fun toolStatus(name: String): Int =
    if (name.contains("search", true) || name.contains("web", true)) R.string.im_thread_tools_running else R.string.im_thread_tools_working

/**
 * Tool calls that stay in the chat: prompts waiting for the user, questions the user already answered,
 * and permission requests the user already settled.
 */
internal fun UIMessagePart.Tool.isThreadPrompt(): Boolean =
    approvalState is ToolApprovalState.Pending || (toolName == ASK_USER && approvalState is ToolApprovalState.Answered) ||
        (isSettledPermission() && !isImageGeneration()) || isPartnerMessage()

/** A permission card the user (or stopping the reply) already settled; it shrinks to a one-line record in place. */
internal fun UIMessagePart.Tool.isSettledPermission(): Boolean {
    if (toolName == ASK_USER || approvalState is ToolApprovalState.Pending) return false
    val record = approvalRecord ?: return false
    return record.decidedAt != null && record.decision != null &&
        record.decision != ToolDecision.ANSWERED && record.decision != ToolDecision.AUTO_ALLOWED
}

@Composable
internal fun ThreadToolPrompt(
    tool: UIMessagePart.Tool,
    onApproval: (String, Boolean) -> Unit,
    onAnswer: (String, String) -> Unit,
    assistant: Assistant? = null,
    /** The segment the tool call belongs to; an interactive terminal only opens for its own chat. */
    conversationId: String? = null,
) {
    when {
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == ASK_USER ->
            ThreadToolDetails(listOf(ThinkingStep.ToolStep(tool)), onAnswer)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == WORKSPACE_TERMINAL_TOOL_NAME ->
            ThreadTerminalCard(tool, conversationId, onApproval, onAnswer)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == UPDATE_MY_PROFILE ->
            ThreadProfileCard(tool, assistant, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == CREATE_PARTNER ->
            ThreadCreatePartnerCard(tool, assistant, onApproval)
        tool.approvalState is ToolApprovalState.Pending && (tool.toolName == CREATE_GROUP_CHAT || tool.toolName == INVITE_TO_GROUP_CHAT) ->
            ThreadGroupChatCard(tool, assistant, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == ORGANIZE_CHAT_LIST ->
            ThreadOrganizeCard(tool, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == DRAFT_BUG_REPORT ->
            ThreadBugReportCard(tool, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.isImageGeneration() ->
            ThreadDrawingSetupCard(tool, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == CALENDAR_CREATE ->
            ThreadCalendarCard(tool, assistant, onApproval)
        tool.approvalState is ToolApprovalState.Pending && tool.toolName == EXTENSIONS_APPLY ->
            ThreadExtensionCard(tool, assistant, onApproval)
        tool.approvalState is ToolApprovalState.Pending -> ThreadGenericApprovalCard(tool, assistant, onApproval)
        tool.toolName == ASK_USER -> ThreadAnsweredQuestions(tool)
        tool.toolName == DRAFT_BUG_REPORT && tool.isSettledPermission() -> ThreadBugReportRecord(tool)
        // The event itself says it was added; the decision stays one tap away in the record sheet.
        tool.addedCalendarEvent() != null -> ThreadCalendarEvent(tool.addedCalendarEvent()!!)
        tool.isSettledPermission() -> ThreadPermissionRecord(tool, assistant)
    }
}

@Composable
internal fun ThreadLiveStatus(@StringRes label: Int) {
    Text(stringResource(label), Modifier.shimmer(isLoading = true), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ThreadToolDetails(steps: List<ThinkingStep>, onAnswer: ((String, String) -> Unit)? = null) {
    ChainOfThought(steps = steps, collapsedVisibleCount = Int.MAX_VALUE) { step ->
        if (step is ThinkingStep.ToolStep) ChatMessageToolStep(step.tool, loading = false, onToolAnswer = onAnswer)
    }
}

/** The question and the user's choice, kept as part of the conversation rather than as process. */
@Composable
private fun ThreadAnsweredQuestions(tool: UIMessagePart.Tool) {
    val answered = tool.approvalState as? ToolApprovalState.Answered ?: return
    val rows = remember(tool.input, answered.answer) {
        val answers = runCatching { Json.parseToJsonElement(answered.answer).jsonObject["answers"]?.jsonObject }.getOrNull()
        runCatching {
            tool.inputAsJson().jsonObject["questions"]?.jsonArray?.map { item ->
                val question = item.jsonObject
                val id = question["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                question["question"]?.jsonPrimitive?.contentOrNull.orEmpty() to
                    (answers?.get(id)?.jsonPrimitive?.contentOrNull ?: answered.answer)
            }
        }.getOrNull().orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { (question, answer) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (question.isNotBlank()) Text(question, style = MaterialTheme.typography.titleMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(answer, Modifier.weight(1f))
                        Icon(HugeIcons.Tick01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * A command the user runs in an interactive terminal (sudo and other prompts). It is settled by the terminal's
 * own result, not by allow/decline: a plain "allow" can never run it, so the card offers open and cancel instead.
 */
@Composable
private fun ThreadTerminalCard(
    tool: UIMessagePart.Tool,
    conversationId: String?,
    onApproval: (String, Boolean) -> Unit,
    onAnswer: (String, String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompositionLocalProvider(LocalTerminalConversationId provides conversationId) {
                ChainOfThought(steps = listOf(ThinkingStep.ToolStep(tool)), collapsedVisibleCount = Int.MAX_VALUE) {
                    ChatMessageToolStep(tool, onToolAnswer = onAnswer,
                        onToolApproval = { id, approved, _ -> onApproval(id, approved) })
                }
            }
            RelayApprovalNotice(tool)
        }
    }
}

private fun webSearchReason(tool: UIMessagePart.Tool): String? = toolArg(tool, "reason")

/** Set by the app when a partner's message, not the owner, started this turn in the owner's private chat. */
internal fun UIMessagePart.Tool.isRelayApproval(): Boolean =
    (metadata?.get("approval_reason") as? JsonPrimitive)?.contentOrNull == "owner_thread_non_owner_turn"

/** Why a step that is usually free asks first: another partner's message started this turn. */
@Composable
internal fun RelayApprovalNotice(tool: UIMessagePart.Tool) {
    if (!tool.isRelayApproval()) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(stringResource(R.string.im_relay_approval_notice), Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** The partner asks to change its own profile: each changed field, as it is now and as it would become. */
@Composable
private fun ThreadProfileCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    AgentChangeCard(tool, onApproval, footer = stringResource(R.string.im_profile_undo)) { ProfileChangeContent(tool, assistant) }
}

/** Each field a profile change touches, from [before] to what the call asks for. */
@Composable
internal fun ProfileChangeContent(tool: UIMessagePart.Tool, before: Assistant?) {
    val newName = toolArg(tool, "name")?.trim()
    val preset = toolArg(tool, "avatar_preset")
    val revisions: RevisionService = koinInject()
    val previous by produceState<Avatar?>(null, before?.id, preset) {
        value = if (preset == "previous" && before != null) runCatching { revisions.previousAvatar(before.id) }.getOrNull() else null
    }
    val avatarChange = preset != null || toolArg(tool, "avatar_emoji") != null
    val newAvatar = requestedAvatar(tool, before?.avatar, previous)
    val currentName = before?.let { partnerName(it) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (newName != null) ChangeField(stringResource(R.string.im_profile_name)) {
            currentName?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                ChangeArrow()
            }
            Text(newName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (avatarChange) ChangeField(stringResource(R.string.im_profile_avatar)) {
            before?.let {
                AssistantAvatar(currentName.orEmpty(), it.avatar, Modifier.size(40.dp))
                ChangeArrow()
            }
            if (newAvatar != null) AssistantAvatar(newName ?: currentName.orEmpty(), newAvatar, Modifier.size(40.dp))
            else Text(stringResource(R.string.im_profile_avatar_previous), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        toolArg(tool, "system_prompt")?.let { ChangeLongText(stringResource(R.string.im_profile_prompt), it) }
    }
}

/**
 * What a settled permission card leaves behind: what was asked and how it was answered,
 * in the same wording as the approval history. Tapping it shows the request in readable form and,
 * for changes the app keeps versions of, a way to undo them.
 */
@Composable
internal fun ThreadPermissionRecord(tool: UIMessagePart.Tool, assistant: Assistant? = null) {
    val record = tool.approvalRecord ?: return
    val decision = record.decision ?: return
    val at = record.decidedAt ?: return
    val change by rememberToolCallChange(tool)
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    val allowed = decision == ToolDecision.ALLOWED
    val declined = decision == ToolDecision.DECLINED
    val reason = (tool.approvalState as? ToolApprovalState.Denied)?.reason?.takeIf { declined && it.isNotBlank() }
    val undone = change?.state == ToolCallChangeState.Undone
    Surface(onClick = { details = true }, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (allowed) HugeIcons.Tick01 else HugeIcons.Cancel01, null, Modifier.padding(top = 2.dp).size(16.dp),
                tint = when {
                    allowed -> MaterialTheme.colorScheme.primary
                    declined -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(toolRecordTitle(tool), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(approvalDecisionText(decision, record.via, at) + reason?.let { ": $it" }.orEmpty() +
                    if (undone) " · " + stringResource(R.string.im_record_undone_tag) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (declined) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        ToolRecordDetails(tool, assistant, change) { details = false }
    }
}

/** The one-line name of a settled request, shared by the record line and its sheet. */
@Composable
internal fun toolRecordTitle(tool: UIMessagePart.Tool): String {
    val context = remember(tool) { ToolUIContext(tool, tool.inputAsJson(), null, false) }
    return when {
        tool.toolName == REQUEST_WEB_SEARCH -> webSearchReason(tool) ?: stringResource(R.string.im_p5_web)
        tool.toolName in AGENT_CHANGE_TOOLS -> toolArg(tool, "reason") ?: stringResource(agentChangeTitle(tool.toolName))
        tool.toolName == DRAFT_BUG_REPORT -> toolArg(tool, "title")?.let { "bug: $it" } ?: stringResource(R.string.im_bug_report_title)
        tool.isComputerTool() -> computerActionText(tool)
        tool.toolName.startsWith("mcp__") -> mcpToolParts(tool.toolName).let { (service, step) ->
            stringResource(R.string.im_record_mcp, service, step) }
        else -> ToolUIRegistry.resolve(tool.toolName).title(context)
    }
}

/**
 * What the partner is doing right now, outside any bubble: its avatar, animated for the phase, then
 * the status. While the thread welcomes the user back, the big partner from the welcome flies here.
 */
@Composable
internal fun ThreadLiveStatusRow(assistant: Assistant?, phase: AssistantGenerationPhase, @StringRes label: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThreadPartnerHandOff(Modifier.size(32.dp)) { modifier ->
            ThreadAvatar(assistant, phase.takeIf { it != AssistantGenerationPhase.None } ?: AssistantGenerationPhase.Waiting, modifier = modifier)
        }
        ThreadLiveStatus(label)
    }
}
