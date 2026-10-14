package me.ayuilos.miffan.ui.im.computer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImPartnerVM
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Tick01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal fun computerSetupProgress(state: ComputerSetupState): Pair<Int, Int> {
    // Without a screen there is no Mac account or screen test; Linux and Windows never have the Mac account.
    val skipped = listOfNotNull(
        ComputerSetupStep.MAC_ACCOUNT.takeIf { !state.isMac || !state.usesScreen },
        ComputerSetupStep.TEST.takeIf { !state.usesScreen },
    )
    val index = state.step.ordinal + 1 - skipped.count { state.step.ordinal > it.ordinal }
    return index to ComputerSetupStep.entries.size - skipped.size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerSetupPage(args: ComputerSetupArgs) {
    val vm: ComputerSetupVM = koinViewModel(key = "setup:$args", parameters = { parametersOf(args) })
    // Null when opened from "my computers": the setup ends by picking any partners, or none.
    val forPartner = args.assistantId
    val partner = forPartner?.let { id ->
        val partnerVM: ImPartnerVM = koinViewModel(key = "partner:$id", parameters = { parametersOf(id) })
        partnerVM.assistant.collectAsStateWithLifecycle().value
    }
    val name = threadAssistantName(partner)
    // "Connect another computer" skips choosing: the list it came from already shows the known ones.
    val addingNew = forPartner == null && args.hostId == null
    // Checking a known computer starts by connecting to it; editing starts at its address.
    val checking = args.hostId != null && !args.edit
    val state by vm.state.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val keys by vm.sshKeys.collectAsStateWithLifecycle()
    val replaced by vm.replacedBinding.collectAsStateWithLifecycle()
    val host = hosts.find { it.id == state.hostId }
    val nav = LocalNavController.current
    val (step, steps) = computerSetupProgress(state)
    val progressLabel = stringResource(R.string.im_computer_step_progress, step, steps)
    LaunchedEffect(Unit) { if (addingNew && state.step == ComputerSetupStep.CHOOSE) vm.addNewComputer() }
    fun back() {
        if (state.busy) return
        val first = state.step in setOf(ComputerSetupStep.CHOOSE, ComputerSetupStep.DONE) ||
            (state.step == ComputerSetupStep.ADDRESS && (addingNew || args.edit)) ||
            // The computer is saved by then and stays in the list, where it can be checked again.
            ((checking || addingNew) && state.step in setOf(ComputerSetupStep.VERIFY, ComputerSetupStep.PREPARE))
        if (first) nav.popBackStack() else vm.back()
    }
    BackHandler { back() }
    Scaffold(topBar = {
        Column {
            // Checking or editing a known computer is about that computer, not connecting a new one.
            TopAppBar(title = { Text(if (args.hostId != null) host?.name.orEmpty() else stringResource(R.string.im_computer_setup_title)) }, navigationIcon = {
                IconButton(onClick = ::back, enabled = !state.busy) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
            })
            LinearProgressIndicator(progress = { step.toFloat() / steps },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = progressLabel },
                drawStopIndicator = {})
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (state.step) {
                ComputerSetupStep.CHOOSE -> when {
                    checking -> ComputerSetupStepLayout(
                        title = stringResource(R.string.im_computer_prepare), supporting = null,
                        state = state, onDismissError = vm::dismissError,
                        primary = SetupAction(stringResource(R.string.im_computer_retry)) {
                            vm.chooseHost(requireNotNull(args.hostId))
                        },
                    ) { SetupCard { SetupComputerRow(host) } }
                    addingNew -> Unit
                    else -> ComputerChooseStep(vm, state, hosts)
                }
                ComputerSetupStep.ADDRESS -> ComputerAddressStep(vm, state, keys, edit = args.edit, host = host)
                ComputerSetupStep.VERIFY -> ComputerVerifyStep(vm, state, host)
                ComputerSetupStep.PREPARE -> ComputerPrepareStep(vm, state)
                ComputerSetupStep.MAC_ACCOUNT -> ComputerMacAccountStep(vm, state, host?.username.orEmpty())
                ComputerSetupStep.TEST -> ComputerTestStep(vm, state)
                ComputerSetupStep.BIND -> if (forPartner == null) ComputerBindManyStep(vm, state, host) else ComputerSetupStepLayout(
                    title = stringResource(R.string.im_computer_give_title, name),
                    supporting = null, state = state, onDismissError = vm::dismissError,
                    primary = SetupAction(stringResource(R.string.im_computer_give_to, name), onClick = { vm.bind(setOf(forPartner)) }),
                ) {
                    SetupCard {
                        SetupComputerRow(host)
                        HorizontalDivider()
                        bindNotes(state.usesScreen).forEach { SetupBullet(stringResource(it)) }
                    }
                    when (val old = replaced) {
                        ReplacedBinding.Phone -> SetupWarning(stringResource(R.string.im_computer_replace_phone, name))
                        is ReplacedBinding.Computer -> SetupWarning(stringResource(R.string.im_computer_replace_binding, name, old.name))
                        null -> Unit
                    }
                }
                ComputerSetupStep.DONE -> if (forPartner == null) ComputerSetupStepLayout(
                    title = null, supporting = null, state = state, onDismissError = vm::dismissError,
                    primary = SetupAction(stringResource(R.string.im_computer_done)) {
                        val hostId = host?.id
                        // A new computer continues to its own page; checking or editing returns where it started.
                        if (addingNew && hostId != null) nav.replace(Screen.ComputerSetup(), Screen.ComputerDetail(hostId), Screen.Home)
                        else nav.popBackStack()
                    },
                ) {
                    SetupDoneHeader(stringResource(R.string.im_computer_connected_title),
                        stringResource(R.string.im_computer_connected_help, host?.name.orEmpty()))
                    state.partnerView?.let { ComputerPartnerView(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
                } else {
                    val assistantId = forPartner
                    val draft = stringResource(if (state.usesScreen) R.string.im_computer_first_message else R.string.im_computer_first_message_no_screen)
                    ComputerSetupStepLayout(
                        title = null, supporting = null, state = state, onDismissError = vm::dismissError,
                        primary = SetupAction(stringResource(R.string.im_computer_go_chat)) {
                            val id = assistantId.toString()
                            nav.removeAll(Screen.Home) { it == Screen.ComputerSetup(id) || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                            nav.navigate(Screen.Thread(id, text = draft))
                        },
                        secondary = if (state.usesScreen) SetupAction(stringResource(R.string.im_computer_view_screen)) {
                            val id = assistantId.toString()
                            nav.replace(Screen.ComputerSetup(id), Screen.PartnerScreen(id), Screen.Home)
                        } else null,
                    ) {
                        SetupDoneHeader(stringResource(R.string.im_computer_done_title),
                            stringResource(if (state.usesScreen) R.string.im_computer_done_help else R.string.im_computer_done_help_no_screen,
                                name, host?.name.orEmpty()))
                        state.partnerView?.let { ComputerPartnerView(it, Modifier.fillMaxWidth().padding(top = 12.dp)) }
                    }
                }
            }
        }
    }
}

/** What giving the computer to a partner means; screenshots only come with the screen. */
private fun bindNotes(usesScreen: Boolean): List<Int> =
    listOfNotNull(R.string.im_computer_bind_screenshots.takeIf { usesScreen }, R.string.im_computer_bind_account, R.string.im_computer_bind_approval)

@Composable
private fun SetupDoneHeader(title: String, help: String) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(HugeIcons.Tick01, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(help, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The last step from "my computers": any partners may get the computer, or none for now. */
@Composable
private fun ComputerBindManyStep(vm: ComputerSetupVM, state: ComputerSetupState, host: RemoteHostEntity?) {
    val assistants = LocalSettings.current.assistants
    val bound by vm.boundPartnerIds.collectAsStateWithLifecycle()
    val workspaces: WorkspaceRepository = koinInject()
    val workspaceList by remember(workspaces) { workspaces.listFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val picked = selected.mapNotNull { id -> assistants.find { it.id.toString() == id && it.id !in bound } }
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_bind_many_title),
        supporting = stringResource(R.string.im_computer_bind_many_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(
            if (picked.isEmpty()) stringResource(R.string.im_computer_bind_none)
            else stringResource(R.string.im_computer_bind_many_confirm, picked.size),
        ) { vm.bind(picked.map { it.id }.toSet()) },
    ) {
        SetupCard { SetupComputerRow(host) }
        SetupCard(padding = 0.dp, spacing = 0.dp) {
            assistants.forEachIndexed { index, partner ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 68.dp))
                val already = partner.id in bound
                val checked = already || partner.id.toString() in selected
                val boundTo = partner.workspaceId?.toString()?.let { id -> workspaceList.find { it.id == id } }
                val note = when {
                    already -> stringResource(R.string.im_computer_already_using)
                    !checked || boundTo == null -> null
                    boundTo.isRemote -> stringResource(R.string.im_computer_replaces_computer)
                    else -> stringResource(R.string.im_computer_replaces_phone)
                }
                val toggle = {
                    val id = partner.id.toString()
                    selected = if (id in selected) selected - id else selected + id
                }
                Surface(onClick = toggle, enabled = !already && !state.busy, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AssistantAvatar(partner.name, partner.avatar, Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(threadAssistantName(partner))
                            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Checkbox(checked, onCheckedChange = { toggle() }, enabled = !already && !state.busy)
                    }
                }
            }
        }
        if (picked.isNotEmpty()) bindNotes(state.usesScreen).forEach { SetupBullet(stringResource(it)) }
    }
}

@Composable
private fun ComputerChooseStep(vm: ComputerSetupVM, state: ComputerSetupState, hosts: List<RemoteHostEntity>) {
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_choose),
        supporting = stringResource(if (hosts.isEmpty()) R.string.im_computer_address_help else R.string.im_computer_choose_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_add), onClick = vm::addNewComputer),
    ) {
        if (hosts.isNotEmpty()) SetupCard(padding = 0.dp, spacing = 0.dp) {
            hosts.forEachIndexed { index, computer ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                Surface(onClick = { vm.chooseHost(computer.id) }, enabled = !state.busy, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(HugeIcons.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(computer.name, style = MaterialTheme.typography.titleMedium)
                            Text("${computer.username}@${computer.host}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SetupDisclosure(stringResource(R.string.im_computer_requirements_title)) {
            Text(stringResource(R.string.im_computer_requirements), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComputerVerifyStep(vm: ComputerSetupVM, state: ComputerSetupState, host: RemoteHostEntity?) {
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_verify),
        supporting = stringResource(R.string.im_computer_verify_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(stringResource(R.string.im_computer_trust_connect), enabled = state.hostKey != null, onClick = vm::trustAndConnect),
        secondary = SetupAction(stringResource(R.string.im_computer_reread_fingerprint), onClick = vm::rereadFingerprint),
    ) {
        if (state.hostKeyChanged) SetupWarning(stringResource(R.string.im_computer_identity_changed))
        SetupCard {
            SetupComputerRow(host)
            state.hostKey?.let { key ->
                HorizontalDivider()
                Text(key.algorithm, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(key.sha256Fingerprint, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            }
        }
        if (state.expectWindows) SetupDisclosure(stringResource(R.string.im_computer_fingerprint_command)) {
            Text(stringResource(R.string.im_computer_fingerprint_windows_help), style = MaterialTheme.typography.bodyMedium)
            RemoteScreenCopyCommand("", WINDOWS_FINGERPRINT_COMMAND)
        } else SetupDisclosure(stringResource(R.string.im_computer_fingerprint_command)) {
            RemoteScreenCopyCommand("", "for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done")
        }
    }
}

internal class SetupAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * One step of the setup: a title and a line of explanation over scrolling content, with the
 * step's actions pinned at the bottom. Progress and errors show right above the actions.
 */
@Composable
internal fun ComputerSetupStepLayout(
    title: String?,
    supporting: String?,
    state: ComputerSetupState,
    onDismissError: () -> Unit,
    primary: SetupAction?,
    secondary: SetupAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resources = LocalResources.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                supporting?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(computerErrorMessage(resources, error), Modifier.weight(1f).padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !state.busy, onClick = onDismissError) { Text(stringResource(R.string.im_computer_dismiss)) }
                    }
                }
            }
            AnimatedVisibility(state.task != null) {
                Text(stringResource(state.task?.label() ?: R.string.im_computer_working), Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            primary?.let { action ->
                Button(onClick = action.onClick, enabled = action.enabled && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp,
                        color = LocalContentColor.current)
                    Text(action.label)
                }
            }
            secondary?.let { action ->
                TextButton(onClick = action.onClick, enabled = action.enabled && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
            }
        }
    }
}

private fun ComputerSetupTask.label(): Int = when (this) {
    ComputerSetupTask.CREATING_KEY -> R.string.im_computer_task_creating_key
    ComputerSetupTask.CONNECTING -> R.string.im_computer_task_connecting
    ComputerSetupTask.READING_FINGERPRINT -> R.string.im_computer_task_fingerprint
    ComputerSetupTask.VERIFYING -> R.string.im_computer_task_verifying
    ComputerSetupTask.PREPARING -> R.string.im_computer_task_checking
    ComputerSetupTask.INSTALLING -> R.string.im_computer_installing
    ComputerSetupTask.CHECKING -> R.string.im_computer_partner_checking
    ComputerSetupTask.SAVING, ComputerSetupTask.BINDING -> R.string.im_computer_task_saving
    ComputerSetupTask.OPENING_SETTINGS -> R.string.im_computer_task_opening_settings
}

/** The IM card: a rounded, low-contrast container for one group of content. */
@Composable
internal fun SetupCard(padding: androidx.compose.ui.unit.Dp = 16.dp, spacing: androidx.compose.ui.unit.Dp = 12.dp,
                       content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
internal fun SetupWarning(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(HugeIcons.AlertCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
internal fun SetupBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupComputerRow(host: RemoteHostEntity?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(HugeIcons.Computer, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(host?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
            host?.let { Text("${it.username}@${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Whether the computer's screen is used. Off suits a server or NAS without a display: partners
 * still use the computer through commands.
 */
@Composable
internal fun ComputerScreenSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.im_computer_use_screen), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (checked) R.string.im_computer_use_screen_on else R.string.im_computer_use_screen_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A collapsed "more about this" row, so a step's main path stays short. */
@Composable
internal fun SetupDisclosure(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        // No background of its own: it sits on the page and inside cards alike.
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(expanded) { Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}
