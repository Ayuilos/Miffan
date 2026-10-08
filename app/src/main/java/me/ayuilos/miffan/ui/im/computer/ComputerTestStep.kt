package me.ayuilos.miffan.ui.im.computer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.pages.extensions.workspace.screen.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.ViewOffSlash
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ComputerTestStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val needsGrants = state.isMac && state.permissions?.complete == false
    ComputerSetupStepLayout(
        title = stringResource(R.string.im_computer_test),
        supporting = stringResource(R.string.im_computer_test_help),
        state = state, onDismissError = vm::dismissError,
        primary = when {
            state.tested -> SetupAction(stringResource(R.string.im_computer_continue), onClick = vm::continueAfterTest)
            needsGrants -> SetupAction(stringResource(R.string.im_computer_request_permissions), onClick = vm::requestMacPermissions)
            else -> SetupAction(stringResource(R.string.im_computer_let_partner_look), onClick = vm::checkPartner)
        },
        secondary = if (needsGrants) SetupAction(stringResource(R.string.im_computer_let_partner_look), onClick = vm::checkPartner) else null,
    ) {
        state.workspaceId?.let { workspaceId -> key(workspaceId) { ComputerTestPreview(workspaceId, state.busy, vm::back) } }
        SetupCard {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Icon(when {
                    state.partnerChecked -> HugeIcons.CheckmarkCircle01
                    needsGrants -> HugeIcons.AlertCircle
                    else -> HugeIcons.ViewOffSlash
                }, null, Modifier.size(20.dp), tint = when {
                    state.partnerChecked -> MaterialTheme.colorScheme.primary
                    needsGrants -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.im_computer_partner_check), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(when {
                        state.task == ComputerSetupTask.CHECKING -> R.string.im_computer_partner_checking
                        state.partnerChecked -> R.string.im_computer_partner_check_ok
                        needsGrants -> R.string.im_computer_permissions_missing
                        else -> R.string.im_computer_partner_check_idle
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.permissions?.takeIf { needsGrants }?.let { grants ->
                        if (!grants.accessibility) Text("· " + stringResource(R.string.im_computer_accessibility), color = MaterialTheme.colorScheme.error)
                        if (!grants.screenRecording) Text("· " + stringResource(R.string.im_computer_screen_recording), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.im_computer_permissions_help), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // The live preview above already shows the screen; this is only proof of what the partner saw.
                state.partnerView?.takeIf { state.partnerChecked }?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.im_computer_partner_view),
                        Modifier.width(96.dp).heightIn(max = 64.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Fit)
                }
            }
        }
    }
}

/** The live screen, interactive so the user can answer dialogs on the computer (macOS grants). */
@Composable
private fun ComputerTestPreview(workspaceId: String, busy: Boolean, onRecheck: () -> Unit) {
    val screen: RemoteScreenVM = koinViewModel(key = workspaceId, parameters = { parametersOf(RemoteScreenArgs(workspaceId)) })
    val resources = LocalResources.current
    val controller by screen.controller.collectAsStateWithLifecycle()
    val state by screen.state.collectAsStateWithLifecycle()
    RemoteScreenVisibility(screen)
    // Returning from account/setup changes reuses this keyed VM, so refresh its session.
    LaunchedEffect(screen) {
        if (screen.state.value != RemoteScreenUiState.Connecting) screen.reconnect()
    }
    val ratio = (state as? RemoteScreenUiState.Connected)?.let { it.width.toFloat() / it.height }?.takeIf { it > 0f } ?: (16f / 10f)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f)).clip(MaterialTheme.shapes.large)) {
                RemoteScreenViewport(screen, false, Modifier.fillMaxSize()) { failed ->
                    RemoteScreenFailureContent(failed, true, screen::reconnect, onSettings = onRecheck,
                        failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let { computerErrorMessage(resources, it) },
                        settingsText = stringResource(R.string.im_computer_recheck))
                }
                if (busy) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) {})
                RemoteScreenControllerBanner(controller, screen::handBackToPartner,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
    }
}

@Composable
internal fun ComputerPartnerView(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(bitmap.asImageBitmap(), stringResource(R.string.im_computer_partner_view),
            Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
    }
}
