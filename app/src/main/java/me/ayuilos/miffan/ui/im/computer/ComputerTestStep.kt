package me.ayuilos.miffan.ui.im.computer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ComputerTestStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    Text(stringResource(R.string.im_computer_test), style = MaterialTheme.typography.headlineSmall)
    state.workspaceId?.let { workspaceId ->
        key(workspaceId) {
            val screen: RemoteScreenVM = koinViewModel(key = workspaceId, parameters = { parametersOf(RemoteScreenArgs(workspaceId)) })
            val resources = LocalResources.current
            val controller by screen.controller.collectAsStateWithLifecycle()
            RemoteScreenVisibility(screen)
            // Returning from account/setup changes reuses this keyed VM, so refresh its session.
            LaunchedEffect(screen) {
                if (screen.state.value != RemoteScreenUiState.Connecting) screen.reconnect()
            }
            RemoteScreenControllerBanner(controller, screen::handBackToPartner)
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                RemoteScreenViewport(screen, false, Modifier.fillMaxSize()) { failed ->
                    RemoteScreenFailureContent(failed, true, screen::reconnect, onSettings = vm::back,
                        failureText = (failed as? RemoteScreenUiState.Failed)?.error?.let { computerErrorMessage(resources, it) }
                            ?: stringResource(R.string.im_computer_disconnected),
                        settingsText = stringResource(R.string.im_computer_recheck),
                    )
                }
                if (state.busy) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) {})
            }
        }
    }
    Text(stringResource(R.string.im_computer_partner_check), style = MaterialTheme.typography.titleMedium)
    TextButton(enabled = !state.busy, onClick = vm::checkPartner) { Text(stringResource(R.string.im_computer_let_partner_look)) }
    state.permissions?.takeIf { state.isMac && !it.complete }?.let { grants ->
        Text(stringResource(R.string.im_computer_permissions_help))
        if (!grants.accessibility) Text(stringResource(R.string.im_computer_accessibility), color = MaterialTheme.colorScheme.error)
        if (!grants.screenRecording) Text(stringResource(R.string.im_computer_screen_recording), color = MaterialTheme.colorScheme.error)
        TextButton(enabled = !state.busy, onClick = vm::requestMacPermissions) { Text(stringResource(R.string.im_computer_request_permissions)) }
    }
    state.partnerView?.let { ComputerPartnerView(it) }
    if (state.tested) ComputerSetupButton(stringResource(R.string.im_computer_continue), state.busy, onClick = vm::continueAfterTest)
}

@Composable
internal fun ComputerPartnerView(bitmap: Bitmap) {
    Column {
        Text(stringResource(R.string.im_computer_partner_view), style = MaterialTheme.typography.titleSmall)
        Image(bitmap.asImageBitmap(), stringResource(R.string.im_computer_partner_view),
            Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
    }
}
