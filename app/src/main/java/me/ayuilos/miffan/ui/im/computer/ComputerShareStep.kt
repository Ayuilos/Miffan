package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tick01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * Giving the computer to partners, the way one picks people to share with: the live screen on top
 * (the partner's own read-only look runs by itself and is confirmed on it), avatars below.
 */
@Composable
internal fun ComputerShareStep(vm: ComputerSetupVM, state: ComputerSetupState, forPartner: Uuid?) {
    val assistants = LocalSettings.current.assistants
    val bound by vm.boundPartnerIds.collectAsStateWithLifecycle()
    val workspaces: WorkspaceRepository = koinInject()
    val workspaceList by remember(workspaces) { workspaces.listFlow() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by rememberSaveable { mutableStateOf(listOfNotNull(forPartner?.toString())) }
    val picked = selected.mapNotNull { id -> assistants.find { it.id.toString() == id && it.id !in bound } }
    // Mini boxes alone never look at the desktop, which may not be set up.
    val desktop = state.usesScreen && !state.spaceOnly
    // The look is read-only, so it runs once by itself.
    var autoChecked by rememberSaveable(state.workspaceId) { mutableStateOf(false) }
    LaunchedEffect(state.busy, autoChecked, desktop) {
        if (desktop && !autoChecked && !state.busy && !state.partnerChecked) {
            autoChecked = true
            vm.checkPartner()
        }
    }
    val first = picked.firstOrNull()
    SetupScreen(
        title = stringResource(R.string.im_computer_share_title),
        subtitle = stringResource(R.string.im_computer_share_help),
        state = state, onDismissError = vm::dismissError,
        hero = {
            val workspaceId = state.workspaceId
            if (desktop && workspaceId != null) key(workspaceId) { LivePreview(workspaceId, state.busy, state.partnerChecked) }
            else DeviceTile(state.system.icon(), size = 72.dp)
        },
        primary = SetupAction(when {
            picked.isEmpty() -> stringResource(R.string.im_computer_pick_someone)
            picked.size == 1 -> stringResource(R.string.im_computer_give_to, threadAssistantName(first))
            else -> stringResource(R.string.im_computer_bind_many_confirm, picked.size)
        }, enabled = picked.isNotEmpty()) { vm.share(picked.map { it.id }.toSet()) },
        // A computer someone already uses is not "kept from partners": it is just done.
        secondary = SetupAction(stringResource(if (bound.isEmpty()) R.string.im_computer_bind_none else R.string.im_computer_done)) {
            vm.bind(emptySet())
        },
    ) {
        PartnerGrid(assistants, bound, selected, enabled = !state.busy) { id ->
            selected = if (id in selected) selected - id else selected + id
        }
        picked.forEach { partner ->
            val boundTo = partner.workspaceId?.toString()?.let { id -> workspaceList.find { it.id == id } } ?: return@forEach
            SetupNote(HugeIcons.Shield01, stringResource(if (boundTo.isRemote) R.string.im_computer_switches_computer
                else R.string.im_computer_switches_phone, threadAssistantName(partner)))
        }
        // How a mini box is used is said on the next screen.
        if (!state.spaceOnly) SetupNote(HugeIcons.Shield01,
            stringResource(if (state.usesScreen) R.string.im_computer_share_note else R.string.im_computer_share_note_no_screen))
    }
}

@Composable
private fun PartnerGrid(assistants: List<Assistant>, bound: Set<Uuid>, selected: List<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    // Four to a row, like picking people in a chat app.
    assistants.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { partner ->
                val already = partner.id in bound
                val checked = already || partner.id.toString() in selected
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .toggleable(checked, enabled = enabled && !already, role = Role.Checkbox) { onToggle(partner.id.toString()) }
                    .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        AssistantAvatar(partner.name, partner.avatar, Modifier.size(64.dp).then(
                            if (checked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier))
                        if (checked) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(HugeIcons.Tick01, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(threadAssistantName(partner), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (already) Text(stringResource(R.string.im_computer_already_using), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The live screen, interactive so the user can answer dialogs on the computer; tagged once the partner saw it too. */
@Composable
private fun LivePreview(workspaceId: String, busy: Boolean, partnerSaw: Boolean) {
    val screen: RemoteScreenVM = koinViewModel(key = workspaceId, parameters = { parametersOf(RemoteScreenArgs(workspaceId)) })
    val resources = LocalResources.current
    val controller by screen.controller.collectAsStateWithLifecycle()
    val state by screen.state.collectAsStateWithLifecycle()
    RemoteScreenVisibility(screen)
    // Coming back from other screens reuses this keyed VM, so refresh its session.
    LaunchedEffect(screen) {
        if (screen.state.value != RemoteScreenUiState.Connecting) screen.reconnect()
    }
    val ratio = (state as? RemoteScreenUiState.Connected)?.let { it.width.toFloat() / it.height }?.takeIf { it > 0f } ?: (16f / 10f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f)).clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        RemoteScreenViewport(screen, false, Modifier.fillMaxSize(), zoomControls = false) { failed ->
            RemoteScreenFailureContent(failed, true, screen::reconnect, onSettings = screen::reconnect,
                failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let { computerErrorMessage(resources, it) },
                settingsText = stringResource(R.string.im_computer_retry))
        }
        if (busy) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) {})
        RemoteScreenControllerBanner(controller, screen::handBackToPartner, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        if (partnerSaw) Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Tick01, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text(stringResource(R.string.im_computer_partner_check_ok), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A small celebration, and what to say first. */
@Composable
internal fun ComputerDoneStep(
    vm: ComputerSetupVM,
    state: ComputerSetupState,
    partner: Assistant?,
    onChat: (String?) -> Unit,
    onScreen: (() -> Unit)?,
    onFinish: () -> Unit,
) {
    val computer = state.target?.name.orEmpty()
    val suggestions = if (state.usesScreen) listOf(R.string.im_computer_first_message, R.string.im_computer_first_message_tidy)
        else listOf(R.string.im_computer_first_message_no_screen)
    SetupScreen(
        title = stringResource(R.string.im_computer_done_title),
        subtitle = if (partner != null) stringResource(if (state.usesScreen) R.string.im_computer_done_help else R.string.im_computer_done_help_no_screen,
            threadAssistantName(partner), computer) else stringResource(R.string.im_computer_connected_help, computer),
        state = state, onDismissError = vm::dismissError, centered = true,
        hero = {
            Row(Modifier.padding(top = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceTile(state.system.icon(), size = 88.dp)
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    HorizontalDivider(Modifier.fillMaxWidth(), thickness = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Tick01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (partner != null) AssistantAvatar(partner.name, partner.avatar, Modifier.size(88.dp))
                else DeviceTile(me.rerere.hugeicons.HugeIcons.Tick01, size = 88.dp)
            }
        },
        primary = if (partner != null) SetupAction(stringResource(R.string.im_computer_go_chat_with, threadAssistantName(partner))) { onChat(null) }
            else SetupAction(stringResource(R.string.im_computer_done), onClick = onFinish),
        secondary = onScreen?.let { SetupAction(stringResource(R.string.im_computer_view_screen), onClick = it) },
    ) {
        if (partner != null) Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.im_computer_try_saying), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            suggestions.forEach { id ->
                val text = stringResource(id)
                Surface(onClick = { onChat(text) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("“$text”", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
