package me.ayuilos.miffan.ui.im.computer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.ayuilos.miffan.ui.context.LocalNavController
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
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal fun computerSetupProgress(state: ComputerSetupState): Pair<Int, Int> {
    val index = state.step.ordinal + 1 - if (!state.isMac && state.step.ordinal > ComputerSetupStep.MAC_ACCOUNT.ordinal) 1 else 0
    return index to if (state.isMac) 8 else 7
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerSetupPage(assistantId: Uuid) {
    val vm: ComputerSetupVM = koinViewModel(key = "setup:$assistantId", parameters = { parametersOf(assistantId) })
    val partnerVM: ImPartnerVM = koinViewModel(key = "partner:$assistantId", parameters = { parametersOf(assistantId) })
    val partner by partnerVM.assistant.collectAsStateWithLifecycle()
    val name = threadAssistantName(partner)
    val state by vm.state.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val keys by vm.sshKeys.collectAsStateWithLifecycle()
    val replaced by vm.replacedWorkspaceName.collectAsStateWithLifecycle()
    val host = hosts.find { it.id == state.hostId }
    val nav = LocalNavController.current
    val (step, steps) = computerSetupProgress(state)
    val progressLabel = stringResource(R.string.im_computer_step_progress, step, steps)
    fun back() {
        if (state.busy) return
        if (state.step in setOf(ComputerSetupStep.CHOOSE, ComputerSetupStep.DONE)) nav.popBackStack() else vm.back()
    }
    BackHandler { back() }
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(stringResource(R.string.im_computer_setup_title)) }, navigationIcon = {
                IconButton(onClick = ::back, enabled = !state.busy) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
            })
            LinearProgressIndicator(progress = { step.toFloat() / steps },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = progressLabel },
                drawStopIndicator = {})
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (state.step) {
                ComputerSetupStep.CHOOSE -> ComputerChooseStep(vm, state, hosts)
                ComputerSetupStep.ADDRESS -> ComputerAddressStep(vm, state, keys)
                ComputerSetupStep.VERIFY -> ComputerVerifyStep(vm, state, host)
                ComputerSetupStep.PREPARE -> ComputerPrepareStep(vm, state)
                ComputerSetupStep.MAC_ACCOUNT -> ComputerMacAccountStep(vm, state, host?.username.orEmpty())
                ComputerSetupStep.TEST -> ComputerTestStep(vm, state)
                ComputerSetupStep.BIND -> ComputerSetupStepLayout(
                    title = stringResource(R.string.im_computer_give_title, name),
                    supporting = null, state = state, onDismissError = vm::dismissError,
                    primary = SetupAction(stringResource(R.string.im_computer_give_to, name), onClick = vm::bind),
                ) {
                    SetupCard {
                        SetupComputerRow(host)
                        HorizontalDivider()
                        listOf(R.string.im_computer_bind_screenshots, R.string.im_computer_bind_account, R.string.im_computer_bind_approval)
                            .forEach { SetupBullet(stringResource(it)) }
                    }
                    replaced?.let { SetupWarning(stringResource(R.string.im_computer_replace_binding, name, it)) }
                }
                ComputerSetupStep.DONE -> {
                    val draft = stringResource(R.string.im_computer_first_message)
                    ComputerSetupStepLayout(
                        title = null, supporting = null, state = state, onDismissError = vm::dismissError,
                        primary = SetupAction(stringResource(R.string.im_computer_go_chat)) {
                            val id = assistantId.toString()
                            nav.removeAll(Screen.Home) { it == Screen.ComputerSetup(id) || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                            nav.navigate(Screen.Thread(id, text = draft))
                        },
                        secondary = SetupAction(stringResource(R.string.im_computer_view_screen)) {
                            val id = assistantId.toString()
                            nav.replace(Screen.ComputerSetup(id), Screen.PartnerScreen(id), Screen.Home)
                        },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Tick01, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text(stringResource(R.string.im_computer_done_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.im_computer_done_help, name, host?.name.orEmpty()), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            state.partnerView?.let { ComputerPartnerView(it, Modifier.padding(top = 12.dp)) }
                        }
                    }
                }
            }
        }
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
        SetupDisclosure(stringResource(R.string.im_computer_fingerprint_command)) {
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
private fun SetupBullet(text: String) {
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

/** A collapsed "more about this" row, so a step's main path stays short. */
@Composable
internal fun SetupDisclosure(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        AnimatedVisibility(expanded) { Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}
