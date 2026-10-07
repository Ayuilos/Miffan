package me.ayuilos.miffan.ui.im.computer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.ImPartnerVM
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.RemoteScreenCopyCommand
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal fun computerSetupProgress(state: ComputerSetupState): Pair<Int, Int> {
    val index = state.step.ordinal + 1 - if (!state.isMac && state.step.ordinal > ComputerSetupStep.MAC_ACCOUNT.ordinal) 1 else 0
    return index to if (state.isMac) 8 else 7
}

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
    val resources = LocalResources.current
    val progress = computerSetupProgress(state)
    fun back() {
        if (state.busy) return
        if (state.step in setOf(ComputerSetupStep.CHOOSE, ComputerSetupStep.DONE)) nav.popBackStack()
        else vm.back()
    }
    BackHandler { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text(stringResource(R.string.im_computer_connect_for, name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.im_computer_step_progress, progress.first, progress.second), style = MaterialTheme.typography.labelMedium)
        } }, navigationIcon = { IconButton(onClick = ::back, enabled = !state.busy) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
            if (state.busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(if (state.task == ComputerSetupTask.INSTALLING) R.string.im_computer_installing else R.string.im_computer_working))
            }
            state.error?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(computerErrorMessage(resources, error))
                        TextButton(enabled = !state.busy, onClick = vm::dismissError) { Text(stringResource(R.string.im_computer_dismiss)) }
                    }
                }
            }
            when (state.step) {
                ComputerSetupStep.CHOOSE -> {
                    Text(stringResource(R.string.im_computer_choose), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.im_computer_requirements), style = MaterialTheme.typography.bodyMedium)
                    hosts.forEach { computer ->
                        OutlinedCard(onClick = { vm.chooseHost(computer.id) }, enabled = !state.busy) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(computer.name, style = MaterialTheme.typography.titleMedium)
                                Text("${computer.username}@${computer.host}:${computer.port}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.error != null && state.hostId != null) {
                        TextButton(enabled = !state.busy, onClick = vm::rereadFingerprint) { Text(stringResource(R.string.im_computer_reread_fingerprint)) }
                    }
                    ComputerSetupButton(stringResource(R.string.im_computer_add), state.busy, onClick = vm::addNewComputer)
                }
                ComputerSetupStep.ADDRESS -> ComputerAddressStep(vm, state, keys)
                ComputerSetupStep.VERIFY -> {
                    Text(stringResource(R.string.im_computer_verify), style = MaterialTheme.typography.headlineSmall)
                    host?.let { Text("${it.username}@${it.host}:${it.port}") }
                    if (state.hostKeyChanged) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.im_computer_identity_changed), Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                    state.hostKey?.let { key ->
                        Text(key.algorithm, fontFamily = FontFamily.Monospace)
                        Text(key.sha256Fingerprint, fontFamily = FontFamily.Monospace)
                    }
                    Text(stringResource(R.string.im_computer_verify_help))
                    RemoteScreenCopyCommand(stringResource(R.string.im_computer_fingerprint_command),
                        "for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done")
                    ComputerSetupButton(stringResource(R.string.im_computer_trust_connect), state.busy,
                        enabled = state.hostKey != null, onClick = vm::trustAndConnect)
                    TextButton(enabled = !state.busy, onClick = vm::rereadFingerprint) { Text(stringResource(R.string.im_computer_reread_fingerprint)) }
                }
                ComputerSetupStep.PREPARE -> ComputerPrepareStep(vm, state)
                ComputerSetupStep.MAC_ACCOUNT -> ComputerMacAccountStep(vm, state, host?.username.orEmpty())
                ComputerSetupStep.TEST -> ComputerTestStep(vm, state)
                ComputerSetupStep.BIND -> {
                    Text(stringResource(R.string.im_computer_give_title, name), style = MaterialTheme.typography.headlineSmall)
                    Text(host?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.im_computer_bind_disclosure))
                    replaced?.let { Text(stringResource(R.string.im_computer_replace_binding, name, it), color = MaterialTheme.colorScheme.error) }
                    ComputerSetupButton(stringResource(R.string.im_computer_give_to, name), state.busy, onClick = vm::bind)
                }
                ComputerSetupStep.DONE -> {
                    Text(stringResource(R.string.im_computer_connected), style = MaterialTheme.typography.headlineSmall)
                    state.partnerView?.let { ComputerPartnerView(it) }
                    val draft = stringResource(R.string.im_computer_first_message)
                    ComputerSetupButton(stringResource(R.string.im_computer_go_chat), state.busy, onClick = {
                        val id = assistantId.toString()
                        nav.removeAll(Screen.Home) { it == Screen.ComputerSetup(id) || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                        nav.navigate(Screen.Thread(id, text = draft))
                    })
                    TextButton(enabled = !state.busy, onClick = {
                        val id = assistantId.toString()
                        nav.replace(Screen.ComputerSetup(id), Screen.PartnerScreen(id), Screen.Home)
                    }) { Text(stringResource(R.string.im_computer_view_screen)) }
                }
            }
        }
    }
}

@Composable
internal fun ComputerSetupButton(text: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        Text(text)
    }
}
