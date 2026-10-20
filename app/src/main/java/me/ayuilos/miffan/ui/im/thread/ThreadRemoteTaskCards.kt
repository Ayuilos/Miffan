package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.parseAgentToolDefinition
import me.ayuilos.miffan.data.ai.tools.defaultTestRun
import me.ayuilos.miffan.data.ai.tools.mergeTaskUpdate
import me.ayuilos.miffan.data.ai.tools.parseScheduleTaskDraft
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.RemoteExecutable
import me.ayuilos.miffan.data.remotetask.RemoteTaskMeta
import me.ayuilos.miffan.data.remotetask.RemoteTaskProbe
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.ResolvedAgentCommand
import me.ayuilos.miffan.data.remotetask.RemoteTaskScript
import me.ayuilos.miffan.data.remotetask.ResultExtraction
import me.ayuilos.miffan.data.remotetask.TaskKind
import me.ayuilos.miffan.data.remotetask.allAgentTools
import me.ayuilos.miffan.data.remotetask.zone
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.remotetask.notifyLabel
import me.ayuilos.miffan.ui.remotetask.permissionLabel
import me.ayuilos.miffan.ui.remotetask.scheduleLabel
import me.ayuilos.miffan.ui.remotetask.zoneNote
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Wrench01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal const val SCHEDULE_TASK = me.ayuilos.miffan.data.ai.tools.SCHEDULE_TASK_TOOL
internal const val AGENT_TOOLS = me.ayuilos.miffan.data.ai.tools.AGENT_TOOLS_TOOL

/**
 * A partner asks to create, change, run, pause or delete a scheduled task. The card shows exactly what
 * will run on which computer and when, and that results arrive only when Miffan is opened.
 */
@Composable
internal fun ThreadScheduleTaskCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    val settings = LocalSettings.current
    val repository: RemoteTaskRepository = koinInject()
    val input = remember(tool.input) { tool.inputAsJson() as? JsonObject ?: JsonObject(emptyMap()) }
    val action = toolArg(tool, "action")
    val hostId = tool.workspaceTarget?.remoteHostId
    val computer = tool.workspaceTarget?.let { it.remoteHostName ?: it.remoteHostLabel }
    val host by remember(hostId) { hostId?.let { repository.hostFlow(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsStateWithLifecycle(initialValue = null)
    val zone = host?.let { RemoteTaskProbe(it.scheduler, timezone = it.timezone, utcOffset = it.utcOffset).zone() }

    val taskId = toolArg(tool, "task_id")
    val tasks by remember(hostId) { hostId?.let { repository.tasksFlow(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(initialValue = null)

    if (action == "create" || action == "update") {
        val tools = settings.allAgentTools()
        // An update may name only what changes: read it against the stored task, and show what it was.
        val stored = if (action == "update") {
            val all = tasks ?: return
            all.find { it.taskId == taskId }?.let { task -> repository.decodeMeta(task)?.let { task to it } }
        } else null
        val owner = remember { RemoteTaskMeta.Owner(Uuid.random()) }
        val before = remember(stored, tools) {
            stored?.let { (task, meta) ->
                runCatching {
                    parseScheduleTaskDraft(mergeTaskUpdate(JsonObject(mapOf("task_id" to JsonPrimitive(task.taskId))), task, meta, tools), tools, owner, true)
                }.getOrNull()
            }
        }
        val draft = remember(input, stored, tools) {
            runCatching {
                val fields = stored?.let { (task, meta) -> mergeTaskUpdate(input, task, meta, tools) } ?: input
                parseScheduleTaskDraft(fields, tools, owner, action == "update")
            }.getOrNull()
        } ?: return ThreadGenericApprovalCardFallback(tool, assistant, onApproval)
        @Composable fun was(old: String?, new: String?): String? =
            if (before != null && old != new) stringResource(R.string.remote_task_field_was, old ?: "—") else null
        val agent = draft.agentToolId?.let { id -> tools.find { it.id == id } }
        val resolved by produceState<ResolvedAgentCommand?>(null, hostId, draft) {
            if (hostId != null && agent != null) value = runCatching {
                repository.resolveAgent(hostId, agent.id, draft.permission, draft.prompt.orEmpty())
            }.getOrNull()
        }
        val argv = resolved?.argv ?: agent?.let { RemoteTaskScript.agentArgv(it, draft.permission, it.executable, draft.prompt.orEmpty()) }
        // The partner's suggestion; the owner decides on the card whether it runs once right away.
        val actions = LocalToolCardActions.current
        var testRun by remember(tool.toolCallId) {
            mutableStateOf((input["test_run"] as? JsonPrimitive)?.booleanOrNull ?: defaultTestRun(action, draft.kind))
        }
        var showCommand by remember(tool.toolCallId) { mutableStateOf(false) }
        ApprovalCardFrame(tool, stringResource(if (action == "create") R.string.remote_task_card_create else R.string.remote_task_card_update, who),
            onApproval, icon = HugeIcons.Clock01, allowDanger = argv?.let(RemoteTaskScript::grantsFullAccess) == true,
            onAllow = actions?.let { cardActions -> { _ ->
                cardActions.approveWithInput(tool.toolCallId, JsonObject(input + ("test_run" to JsonPrimitive(testRun))))
            } }) {
            ApprovalFields(listOfNotNull(
                stringResource(R.string.remote_task_field_name) to @Composable { FieldText(draft.name, detail = was(before?.name, draft.name)) },
                computer?.let { stringResource(R.string.remote_task_field_computer) to @Composable { FieldText(it) } },
                stringResource(R.string.remote_task_field_time) to @Composable {
                    val label = scheduleLabel(draft.schedule)
                    FieldText(label, detail = was(before?.schedule?.let { scheduleLabel(it) }, label) ?: zoneNote(draft.schedule, zone))
                },
                if (agent != null) stringResource(R.string.remote_task_field_agent) to @Composable {
                    val level = agent.permissions.find { it.name == draft.permission }
                    val label = listOfNotNull(agent.name, permissionLabel(draft.permission)).joinToString(" · ")
                    val old = before?.let { b ->
                        b.agentToolId?.let { id -> listOfNotNull(tools.find { it.id == id }?.name, permissionLabel(b.permission)).joinToString(" · ") }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = if (level?.writes == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                        was(old, label)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else stringResource(R.string.remote_task_field_command) to @Composable {
                    FieldText(draft.command.orEmpty(), detail = was(before?.command, draft.command), mono = true, maxLines = 8)
                },
                stringResource(R.string.remote_task_field_result) to @Composable {
                    val partner = assistant?.let { partnerName(it) }
                    val label = notifyLabel(draft.notify, partner)
                    FieldText(label, detail = was(before?.notify?.let { notifyLabel(it, partner) }, label))
                },
            ))
            draft.prompt?.let { prompt ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(prompt, style = MaterialTheme.typography.bodyMedium)
                        was(before?.prompt, prompt)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // Found nowhere on the computer: saving would fail, so say it before the owner allows.
            if (agent != null && resolved != null && !resolved!!.found) {
                DangerNote(stringResource(R.string.remote_task_agent_missing, agent.executable, computer ?: ""))
            }
            if (argv != null) {
                TextButton(onClick = { showCommand = !showCommand }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(stringResource(R.string.remote_task_full_command))
                }
                if (showCommand) CommandLine(argv.joinToString(" ") { RemoteTaskScript.quote(it) })
                if (RemoteTaskScript.grantsFullAccess(argv)) DangerNote(stringResource(R.string.remote_task_danger_full_access, agent?.name.orEmpty()))
            }
            if (actions != null) Surface(onClick = { testRun = !testRun }, color = androidx.compose.ui.graphics.Color.Transparent,
                shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = testRun, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.remote_task_test_run_option), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.remote_task_test_run_option_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            NoteLines(listOfNotNull(
                if (testRun && actions == null) HugeIcons.PlayCircle to stringResource(R.string.remote_task_note_test_run) else null,
                HugeIcons.Refresh to stringResource(R.string.remote_task_note_sync),
                agent?.let {
                    // A user-defined tool is a name, not a product someone signs in to.
                    HugeIcons.InformationCircle to stringResource(
                        if (it.isPreset) R.string.remote_task_note_billing else R.string.remote_task_note_billing_custom, it.name)
                },
            ))
        }
        return
    }
    val name = tasks?.find { it.taskId == taskId }?.let { repository.decodeMeta(it)?.name } ?: taskId.orEmpty()
    val title = when (action) {
        "delete" -> R.string.remote_task_card_delete
        "run_now" -> R.string.remote_task_card_run_now
        "enable" -> R.string.remote_task_card_enable
        "disable" -> R.string.remote_task_card_disable
        else -> return ThreadGenericApprovalCardFallback(tool, assistant, onApproval)
    }
    ApprovalCardFrame(tool, stringResource(title, who), onApproval, icon = HugeIcons.Clock01) {
        ApprovalFields(listOfNotNull(
            stringResource(R.string.remote_task_field_name) to @Composable { FieldText(name) },
            computer?.let { stringResource(R.string.remote_task_field_computer) to @Composable { FieldText(it) } },
        ))
        if (action == "run_now") NoteLines(listOf(HugeIcons.PlayCircle to stringResource(R.string.remote_task_note_run_now)))
    }
}

/**
 * A partner asks to add, change or delete one of the user's agent tools. Shows the command line each
 * permission level runs, what changed, and a warning when a level lets the agent skip every question.
 */
@Composable
internal fun ThreadAgentToolCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    val settings = LocalSettings.current
    val repository: RemoteTaskRepository = koinInject()
    val actions = LocalToolCardActions.current
    val input = remember(tool.input) { tool.inputAsJson() as? JsonObject ?: JsonObject(emptyMap()) }
    val tools = settings.allAgentTools()
    val existing = toolArg(tool, "tool_id")?.let { id -> tools.find { it.id == id } }
    val hostId = tool.workspaceTarget?.remoteHostId
    val computer = tool.workspaceTarget?.let { it.remoteHostName ?: it.remoteHostLabel }
    val using by produceState(0, existing?.id) { value = existing?.let { repository.tasksUsing(it.id).size } ?: 0 }

    if (toolArg(tool, "action") == "delete") {
        ApprovalCardFrame(tool, stringResource(R.string.agent_tool_card_delete, who, existing?.name.orEmpty()), onApproval,
            icon = HugeIcons.Wrench01, allowLabel = stringResource(R.string.agent_tool_delete)) {
            if (using > 0) NoteLines(listOf(HugeIcons.InformationCircle to stringResource(R.string.agent_tool_note_delete_used, using)))
        }
        return
    }
    val definition = remember(input, tools) {
        runCatching { parseAgentToolDefinition(input, tools, assistant?.id ?: Uuid.random()) }.getOrNull()
    } ?: return ThreadGenericApprovalCardFallback(tool, assistant, onApproval)
    val found by produceState<RemoteExecutable?>(null, hostId, definition.executable) {
        if (hostId != null) value = runCatching { repository.resolveExecutable(hostId, definition) }.getOrNull()
    }
    val dangerous = (definition.args + definition.permissions.flatMap { it.args }).any { it in AgentToolDefinition.DANGEROUS_ARGS }
    var updateTasks by remember(tool.toolCallId) {
        mutableStateOf((input["update_tasks"] as? JsonPrimitive)?.booleanOrNull != false)
    }
    val offerUpdate = existing != null && using > 0 && actions != null
    ApprovalCardFrame(
        tool,
        if (existing == null) stringResource(R.string.agent_tool_card_add, who) else stringResource(R.string.agent_tool_card_change, who, existing.name),
        onApproval,
        icon = HugeIcons.Wrench01,
        allowLabel = stringResource(when {
            dangerous -> R.string.agent_tool_allow_anyway
            existing == null -> R.string.agent_tool_add
            else -> R.string.agent_tool_change
        }),
        allowDanger = dangerous,
        onAllow = if (offerUpdate) { _ ->
            actions!!.approveWithInput(tool.toolCallId, JsonObject(input + ("update_tasks" to JsonPrimitive(updateTasks))))
        } else null,
    ) {
        ApprovalFields(listOfNotNull(
            stringResource(R.string.agent_tool_field_name) to @Composable { FieldText(definition.name) },
            stringResource(R.string.agent_tool_field_program) to @Composable {
                val status = when {
                    hostId == null || computer == null -> null
                    found == null -> stringResource(R.string.agent_tool_found_checking, computer)
                    found!!.path == null -> stringResource(R.string.agent_tool_found_missing, computer)
                    else -> stringResource(R.string.agent_tool_found, computer, found!!.version ?: found!!.path!!)
                }
                FieldText(definition.executable, detail = status, mono = true)
            },
            stringResource(R.string.agent_tool_field_result) to @Composable { FieldText(extractionLabel(definition.extraction)) },
        ))
        Text(stringResource(R.string.agent_tool_runs_as), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommandLine(commandPreview(definition, stringResource(R.string.agent_tool_prompt_placeholder)))
        if (!definition.takesPromptArgument) Text(stringResource(R.string.agent_tool_prompt_stdin), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (definition.permissions.isNotEmpty()) {
            Text(stringResource(R.string.agent_tool_levels), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionLevels(definition)
        }
        existing?.let { old -> ToolDiff(old, definition) }
        if (dangerous) DangerNote(stringResource(R.string.remote_task_danger_full_access, definition.name))
        if (offerUpdate) Surface(onClick = { updateTasks = !updateTasks }, color = androidx.compose.ui.graphics.Color.Transparent,
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = updateTasks, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_tool_update_tasks, using), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.agent_tool_update_tasks_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (existing == null) NoteLines(listOf(HugeIcons.InformationCircle to stringResource(R.string.agent_tool_note_after)))
    }
}

/** Arguments a person can read: the prompt shown as a placeholder, every value quoted like the shell. */
internal fun commandPreview(tool: AgentToolDefinition, placeholder: String): String =
    (listOf(tool.executable) + tool.args).joinToString(" ") {
        if (it == AgentToolDefinition.PROMPT_PLACEHOLDER) placeholder else RemoteTaskScript.quote(it).let { q ->
            if (q == "'$it'" && it.none { c -> c.isWhitespace() || c in "\"\\\$`*?[]{}()<>|&;#~" }) it else q
        }
    }

@Composable
internal fun extractionLabel(extraction: ResultExtraction): String = when (extraction) {
    ResultExtraction.Text -> stringResource(R.string.agent_tool_result_text)
    is ResultExtraction.JsonField -> stringResource(R.string.agent_tool_result_json, extraction.field)
    is ResultExtraction.JsonLines -> stringResource(R.string.agent_tool_result_jsonl, extraction.field)
}

@Composable
internal fun PermissionLevels(definition: AgentToolDefinition) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            definition.permissions.forEachIndexed { index, level ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(permissionLabel(level.name).orEmpty(), Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(if (level.args.isEmpty()) stringResource(R.string.agent_tool_level_no_args) else "+ " + level.args.joinToString(" "),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (level.writes) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.agent_tool_level_writes), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

/** What a change does, line by line: the command and each level whose arguments differ. */
@Composable
private fun ToolDiff(old: AgentToolDefinition, new: AgentToolDefinition) {
    val placeholder = stringResource(R.string.agent_tool_prompt_placeholder)
    val rows = buildList {
        val before = commandPreview(old, placeholder)
        val after = commandPreview(new, placeholder)
        if (before != after) add(Triple(null, before, after))
        (old.permissions.map { it.name } + new.permissions.map { it.name }).distinct().forEach { name ->
            val a = old.permissions.find { it.name == name }?.args?.joinToString(" ")
            val b = new.permissions.find { it.name == name }?.args?.joinToString(" ")
            if (a != b) add(Triple(name, a, b))
        }
    }
    if (rows.isEmpty()) return
    Text(stringResource(R.string.agent_tool_changes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    rows.forEach { (level, before, after) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            level?.let { Text(permissionLabel(it).orEmpty(), style = MaterialTheme.typography.bodySmall) }
            before?.let { DiffLine("− ", it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
            after?.let { DiffLine("+ ", it, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

@Composable
private fun DiffLine(mark: String, text: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = background, shape = MaterialTheme.shapes.small) {
        Text(mark + text.ifEmpty { "∅" }, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = content)
    }
}

@Composable
internal fun CommandLine(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun DangerNote(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(HugeIcons.Alert02, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun NoteLines(lines: List<Pair<ImageVector, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Arguments the app cannot read (an older or malformed call, a task no longer on the computer): still
 * a card a person can follow, with the task's name when the call gives one. Allowing lets the tool
 * explain what is wrong to the partner.
 */
@Composable
private fun ThreadGenericApprovalCardFallback(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    val schedule = tool.toolName == SCHEDULE_TASK
    val title = when {
        schedule && toolArg(tool, "action") == "create" -> stringResource(R.string.remote_task_card_create, who)
        schedule && toolArg(tool, "action") == "update" -> stringResource(R.string.remote_task_card_update, who)
        tool.toolName == AGENT_TOOLS && toolArg(tool, "action") == "save" -> toolArg(tool, "tool_id")
            ?.let { stringResource(R.string.agent_tool_card_change, who, toolArg(tool, "name").orEmpty()) }
            ?: stringResource(R.string.agent_tool_card_add, who)
        else -> stringResource(R.string.im_approval_tool, who, tool.toolName)
    }
    ApprovalCardFrame(tool, title, onApproval, icon = if (schedule) HugeIcons.Clock01 else HugeIcons.Wrench01) {
        toolArg(tool, "name")?.let { name ->
            ApprovalFields(listOf(stringResource(R.string.remote_task_field_name) to @Composable { FieldText(name) }))
        }
    }
}
