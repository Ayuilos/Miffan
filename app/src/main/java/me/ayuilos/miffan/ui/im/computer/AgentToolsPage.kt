package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.remotetask.AgentToolDefinition
import me.ayuilos.miffan.data.remotetask.RemoteExecutable
import me.ayuilos.miffan.data.remotetask.RemoteTaskRepository
import me.ayuilos.miffan.data.remotetask.ResultExtraction
import me.ayuilos.miffan.data.remotetask.allAgentTools
import me.ayuilos.miffan.data.repository.RemoteScreenPlatform
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.partnerName
import me.ayuilos.miffan.ui.im.thread.CommandLine
import me.ayuilos.miffan.ui.im.thread.PermissionLevels
import me.ayuilos.miffan.ui.im.thread.commandPreview
import me.ayuilos.miffan.ui.im.thread.extractionLabel
import me.ayuilos.miffan.ui.remotetask.permissionLabel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Message01
import org.koin.compose.koinInject

/** Partners that can be asked to add a tool: each needs a Mac or Linux computer to look it up on. */
@Composable
private fun partnersWithComputers(): List<Assistant> {
    val workspaces: WorkspaceRepository = koinInject()
    val assistants = LocalSettings.current.assistants
    val list by remember(workspaces) { workspaces.listFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val hosts by remember(workspaces) { workspaces.listHostsFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return assistants.filter { assistant ->
        val workspace = list.find { it.id == assistant.workspaceId?.toString() } ?: return@filter false
        val host = hosts.find { it.id == workspace.remoteHostId } ?: return@filter false
        me.rerere.workspace.RemotePlatform.parse(host.platform) != me.rerere.workspace.RemotePlatform.WINDOWS
    }
}

/** The agent tools scheduled tasks can run: built-in presets and the user's own, mostly added by partners. */
@Composable
fun AgentToolsPage() {
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val partners = partnersWithComputers()
    var picking by rememberSaveable { mutableStateOf(false) }
    val prefill = stringResource(R.string.agent_tools_prefill)
    fun ask(partner: Assistant) = nav.navigate(Screen.Thread(partner.id.toString(), text = prefill))
    Scaffold(topBar = { ImPageBar(stringResource(R.string.agent_tools_title)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("intro") {
                Text(stringResource(R.string.agent_tools_intro), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item("ask") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (partners.size == 1) ask(partners.single()) else picking = true }, enabled = partners.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(HugeIcons.Message01, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.agent_tools_ask_partner))
                    }
                    Text(stringResource(if (partners.isEmpty()) R.string.agent_tools_ask_needs_computer else R.string.agent_tools_ask_help),
                        Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("builtin_title") { SectionTitle(stringResource(R.string.agent_tools_builtin)) }
            item("builtin") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    settings.allAgentTools().filter { it.isPreset }.forEachIndexed { index, tool ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp))
                        ToolRow(tool, tool.permissions.mapNotNull { permissionLabel(it.name) }.joinToString(" · ")) {
                            nav.navigate(Screen.AgentToolEdit(toolId = tool.id))
                        }
                    }
                }
            }
            item("mine_title") { SectionTitle(stringResource(R.string.agent_tools_mine)) }
            item("mine") {
                SetupCard(padding = 0.dp, spacing = 0.dp) {
                    settings.agentTools.forEach { tool ->
                        val origin = tool.addedBy?.let { id -> settings.assistants.find { it.id == id } }
                        ToolRow(tool, origin?.let { stringResource(R.string.agent_tools_added_by, partnerName(it)) }
                            ?: stringResource(R.string.agent_tools_added_by_you)) {
                            nav.navigate(Screen.AgentToolEdit(toolId = tool.id))
                        }
                        HorizontalDivider(Modifier.padding(start = 70.dp))
                    }
                    Surface(onClick = { nav.navigate(Screen.AgentToolEdit()) }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Add01, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(stringResource(R.string.agent_tools_add_manually), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    if (picking) AlertDialog(onDismissRequest = { picking = false },
        title = { Text(stringResource(R.string.agent_tools_pick_partner)) },
        text = {
            Column {
                partners.forEach { partner ->
                    Surface(onClick = { picking = false; ask(partner) }, color = androidx.compose.ui.graphics.Color.Transparent,
                        shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AssistantAvatar(partner.name, partner.avatar, Modifier.size(36.dp))
                            Text(partnerName(partner))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToolRow(tool: AgentToolDefinition, supporting: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AgentToolLogo(tool)
            Column(Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tool's mark: the vendor's real logo for known agents (the app's AI icon set), initials otherwise. */
@Composable
internal fun AgentToolLogo(tool: AgentToolDefinition) {
    val brand = when (tool.id) {
        "preset:claude-code" -> "claude"
        "preset:codex" -> "openai"
        "preset:gemini" -> "gemini"
        else -> tool.name
    }
    AutoAIIcon(brand, Modifier.size(40.dp))
}

/**
 * Manual editor for an agent tool, the advanced path next to asking a partner. Presets open read-only
 * with a copy button. A change to a tool tasks use asks whether to update those tasks too.
 */
@Composable
fun AgentToolEditPage(toolId: String?, copyFrom: String?) {
    val nav = LocalNavController.current
    val resources = LocalResources.current
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val repository: RemoteTaskRepository = koinInject()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val all = settings.allAgentTools()
    val existing = toolId?.let { id -> all.find { it.id == id } }
    val source = existing ?: copyFrom?.let { id -> all.find { it.id == id } }
    val readOnly = existing?.isPreset == true
    var name by rememberSaveable { mutableStateOf(existing?.name ?: source?.let { resources.getString(R.string.agent_tools_copy_name, it.name) }.orEmpty()) }
    var executable by rememberSaveable { mutableStateOf(source?.executable.orEmpty()) }
    var args by rememberSaveable { mutableStateOf(source?.args?.joinToString("\n").orEmpty()) }
    var levels by remember { mutableStateOf(source?.permissions?.map { LevelDraft(it.name, it.args.joinToString("\n"), it.writes) }.orEmpty()) }
    var resultType by rememberSaveable { mutableStateOf(when (source?.extraction) { is ResultExtraction.JsonField -> "json"; is ResultExtraction.JsonLines -> "jsonl"; else -> "text" }) }
    var field by rememberSaveable { mutableStateOf(when (val e = source?.extraction) { is ResultExtraction.JsonField -> e.field; is ResultExtraction.JsonLines -> e.field; else -> "" }) }
    var match by rememberSaveable { mutableStateOf((source?.extraction as? ResultExtraction.JsonLines)?.match?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty()) }
    var errorField by rememberSaveable { mutableStateOf(when (val e = source?.extraction) { is ResultExtraction.JsonField -> e.errorField; is ResultExtraction.JsonLines -> e.errorField; else -> null }.orEmpty()) }
    var extraEnv by rememberSaveable { mutableStateOf(source?.extraEnv?.joinToString("\n").orEmpty()) }
    var versionArg by rememberSaveable { mutableStateOf(source?.versionArg ?: "--version") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var updateAsk by remember { mutableStateOf<Int?>(null) }
    val computers by rememberKnownComputers()
    val checkable = computers.orEmpty().filter { it.platform != RemoteScreenPlatform.WINDOWS }
    var checkHost by rememberSaveable { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<RemoteExecutable?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun build(): AgentToolDefinition = AgentToolDefinition(
        id = existing?.takeUnless { it.isPreset }?.id ?: kotlin.uuid.Uuid.random().toString(),
        name = name.trim(),
        executable = executable.trim(),
        args = lines(args),
        permissions = levels.map { AgentToolDefinition.Permission(it.name.trim(), lines(it.args), it.writes) },
        extraction = when (resultType) {
            "json" -> ResultExtraction.JsonField(field.trim(), errorField = errorField.trim().ifEmpty { null })
            "jsonl" -> ResultExtraction.JsonLines(lines(match).associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() },
                field.trim(), errorField = errorField.trim().ifEmpty { null })
            else -> ResultExtraction.Text
        },
        versionArg = versionArg.trim().ifEmpty { null },
        extraEnv = lines(extraEnv),
        addedBy = existing?.addedBy,
    ).also { tool ->
        tool.validate()
        require(all.none { it.name.equals(tool.name, ignoreCase = true) && it.id != tool.id }) {
            resources.getString(R.string.agent_tools_name_taken, tool.name)
        }
    }

    Scaffold(topBar = {
        ImPageBar(stringResource(if (existing == null) R.string.agent_tools_add_manually else R.string.agent_tools_edit_title)) {
            if (!readOnly) TextButton(onClick = {
                val tool = runCatching { build() }.getOrElse { scope.launch { snackbar.showSnackbar(it.message.orEmpty()) }; return@TextButton }
                scope.launch {
                    settingsStore.update { s -> s.copy(agentTools = s.agentTools.filterNot { it.id == tool.id } + tool) }
                    val using = if (existing != null && existing != tool) repository.tasksUsing(tool.id).size else 0
                    if (using > 0) updateAsk = using else nav.popBackStack()
                }
            }) { Text(stringResource(R.string.agent_tools_save)) }
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (readOnly && existing != null) {
                Text(existing.name, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.agent_tool_runs_as), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommandLine(commandPreview(existing, stringResource(R.string.agent_tool_prompt_placeholder)))
                if (existing.permissions.isNotEmpty()) PermissionLevels(existing)
                Text(extractionLabel(existing.extraction), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { nav.navigate(Screen.AgentToolEdit(copyFrom = existing.id)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(stringResource(R.string.agent_tools_copy)) }
                Text(stringResource(R.string.agent_tools_builtin_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tool_field_name)) }, singleLine = true)
            OutlinedTextField(executable, { executable = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tool_field_program)) },
                supportingText = { Text(stringResource(R.string.agent_tools_program_help)) }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tools_args)) },
                supportingText = { Text(stringResource(R.string.agent_tools_args_help)) }, minLines = 3,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))

            SectionTitle(stringResource(R.string.agent_tool_levels))
            levels.forEachIndexed { index, level ->
                SetupCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(level.name, { v -> levels = levels.toMutableList().also { it[index] = level.copy(name = v) } },
                            Modifier.weight(1f), label = { Text(stringResource(R.string.agent_tools_level_name)) }, singleLine = true)
                        IconButton(onClick = { levels = levels.filterIndexed { i, _ -> i != index } }) {
                            Icon(HugeIcons.Delete02, stringResource(R.string.agent_tool_delete))
                        }
                    }
                    OutlinedTextField(level.args, { v -> levels = levels.toMutableList().also { it[index] = level.copy(args = v) } },
                        Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tools_level_args)) }, minLines = 2,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.agent_tool_level_writes), Modifier.weight(1f))
                        Switch(level.writes, { v -> levels = levels.toMutableList().also { it[index] = level.copy(writes = v) } })
                    }
                }
            }
            TextButton(onClick = { levels = levels + LevelDraft("", "", false) }) { Text(stringResource(R.string.agent_tools_add_level)) }

            SectionTitle(stringResource(R.string.agent_tools_result_where))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("text" to R.string.agent_tools_result_text, "json" to R.string.agent_tools_result_json, "jsonl" to R.string.agent_tools_result_jsonl)
                    .forEachIndexed { index, (type, label) ->
                        SegmentedButton(selected = resultType == type, onClick = { resultType = type },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(stringResource(label)) }
                    }
            }
            if (resultType != "text") {
                OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tools_result_field)) }, singleLine = true)
                if (resultType == "jsonl") OutlinedTextField(match, { match = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_tools_result_match)) }, minLines = 2)
                OutlinedTextField(errorField, { errorField = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_tools_result_error_field)) }, singleLine = true)
            }

            Surface(onClick = { advanced = !advanced }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.agent_tools_inherit))
                    Text(stringResource(R.string.agent_tools_inherit_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (advanced) {
                OutlinedTextField(extraEnv, { extraEnv = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tools_extra_env)) },
                    supportingText = { Text(stringResource(R.string.agent_tools_extra_env_help)) }, minLines = 2,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                OutlinedTextField(versionArg, { versionArg = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.agent_tools_version_arg)) },
                    singleLine = true)
            }

            if (checkable.isNotEmpty()) SetupCard {
                Text(stringResource(R.string.agent_tools_check), style = MaterialTheme.typography.titleSmall)
                val host = checkable.find { it.hostId == checkHost } ?: checkable.first()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    checkable.take(3).forEach { computer ->
                        FilterChip(selected = computer.hostId == host.hostId, onClick = { checkHost = computer.hostId; found = null },
                            label = { Text(computer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                OutlinedButton(onClick = {
                    val tool = runCatching { build() }.getOrElse { scope.launch { snackbar.showSnackbar(it.message.orEmpty()) }; return@OutlinedButton }
                    checking = true
                    scope.launch {
                        found = runCatching { repository.resolveExecutable(host.hostId, tool) }
                            .onFailure { snackbar.showSnackbar(it.message.orEmpty()) }.getOrNull()
                        checking = false
                    }
                }, enabled = !checking) { Text(stringResource(if (checking) R.string.remote_task_loading_log else R.string.agent_tools_check_run)) }
                found?.let { exe ->
                    Text(exe.path?.let { stringResource(R.string.agent_tools_check_found, it, exe.version.orEmpty()) }
                        ?: stringResource(R.string.agent_tool_found_missing, host.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exe.path == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (existing != null) Surface(onClick = { deleting = true }, color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.agent_tools_delete), Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (deleting && existing != null) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.agent_tools_delete_title, existing.name)) },
        text = { Text(stringResource(R.string.agent_tools_delete_body)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                scope.launch {
                    settingsStore.update { s -> s.copy(agentTools = s.agentTools.filterNot { it.id == existing.id }) }
                    nav.popBackStack()
                }
            }) { Text(stringResource(R.string.agent_tool_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.common_cancel)) } })
    updateAsk?.let { count ->
        AlertDialog(onDismissRequest = { updateAsk = null; nav.popBackStack() },
            title = { Text(stringResource(R.string.agent_tool_update_tasks, count)) },
            text = { Text(stringResource(R.string.agent_tool_update_tasks_help)) },
            confirmButton = {
                TextButton(onClick = {
                    updateAsk = null
                    scope.launch {
                        val failed = repository.retarget(existing!!.id)
                        if (failed.isNotEmpty()) snackbar.showSnackbar(resources.getString(R.string.agent_tools_update_failed, failed.keys.joinToString()))
                        nav.popBackStack()
                    }
                }) { Text(stringResource(R.string.agent_tools_update)) }
            },
            dismissButton = { TextButton(onClick = { updateAsk = null; nav.popBackStack() }) { Text(stringResource(R.string.agent_tools_keep)) } })
    }
}

private data class LevelDraft(val name: String, val args: String, val writes: Boolean)
