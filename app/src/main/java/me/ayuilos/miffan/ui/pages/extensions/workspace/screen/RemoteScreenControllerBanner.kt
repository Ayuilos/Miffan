package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import kotlinx.coroutines.delay
import me.ayuilos.miffan.data.ai.computer.RemoteController

private const val PARTNER_LINGER_MILLIS = 3_000L

@Composable
internal fun RemoteScreenControllerBanner(
    controller: RemoteController,
    onHandBack: () -> Unit,
    /** The partner's turn is still running on this computer, between its individual actions. */
    partnerBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The controller is PARTNER only while one action runs (often a few milliseconds), so the
    // banner follows the partner's whole turn when known and lingers after its last action.
    val raw = if (controller == RemoteController.IDLE && partnerBusy) RemoteController.PARTNER else controller
    var shown by remember { mutableStateOf(raw) }
    LaunchedEffect(raw) {
        if (raw == RemoteController.IDLE && shown == RemoteController.PARTNER) delay(PARTNER_LINGER_MILLIS)
        shown = raw
    }
    // Keep the last visible message during the exit transition.
    var visibleController by remember { mutableStateOf(shown) }
    if (shown != RemoteController.IDLE && visibleController != shown) visibleController = shown
    AnimatedVisibility(
        visible = shown != RemoteController.IDLE,
        modifier = modifier,
        enter = slideInVertically(tween(150)) { -it } + fadeIn(tween(150)),
        exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(150)),
    ) {
        // A floating pill over the screen: showing or hiding it never moves the picture underneath.
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, shadowElevation = 3.dp) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = if (visibleController == RemoteController.USER) 4.dp else 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visibleController == RemoteController.PARTNER) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    stringResource(if (visibleController == RemoteController.PARTNER) {
                        R.string.workspace_screen_partner_operating
                    } else R.string.workspace_screen_user_operating),
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (visibleController == RemoteController.USER) {
                    TextButton(onClick = onHandBack, enabled = shown == RemoteController.USER) {
                        Text(stringResource(R.string.workspace_screen_hand_back))
                    }
                }
            }
        }
    }
}
