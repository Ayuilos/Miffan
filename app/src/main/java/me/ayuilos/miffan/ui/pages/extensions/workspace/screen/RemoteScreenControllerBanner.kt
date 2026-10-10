package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
/** How long "you are operating" stays spelled out before only the hand-back button remains. */
internal const val USER_NOTICE_MILLIS = 2_500L

/**
 * Who the page should say is driving. The user holding the desktop only matters while the
 * partner works or is waiting for it back; otherwise it is ordinary use and says nothing.
 */
internal fun shownController(controller: RemoteController, partnerBusy: Boolean, partnerWaiting: Boolean): RemoteController = when {
    controller == RemoteController.IDLE && partnerBusy -> RemoteController.PARTNER
    controller == RemoteController.USER && !partnerBusy && !partnerWaiting -> RemoteController.IDLE
    else -> controller
}

@Composable
internal fun RemoteScreenControllerBanner(
    controller: RemoteController,
    onHandBack: () -> Unit,
    /** The partner's turn is still running on this computer, between its individual actions. */
    partnerBusy: Boolean = false,
    /** The partner tried to act while the user held the desktop. */
    partnerWaiting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The controller is PARTNER only while one action runs (often a few milliseconds), so the
    // banner follows the partner's whole turn when known and lingers after its last action.
    val raw = shownController(controller, partnerBusy, partnerWaiting)
    var shown by remember { mutableStateOf(raw) }
    LaunchedEffect(raw) {
        if (raw == RemoteController.IDLE && shown == RemoteController.PARTNER) delay(PARTNER_LINGER_MILLIS)
        shown = raw
    }
    // Keep the last visible message during the exit transition.
    var visibleController by remember { mutableStateOf(shown) }
    if (shown != RemoteController.IDLE && visibleController != shown) visibleController = shown
    // Spelled out when the user takes over or the partner starts waiting, then only "hand back".
    var spelled by remember { mutableStateOf(true) }
    LaunchedEffect(shown, partnerWaiting) {
        spelled = true
        if (shown == RemoteController.USER) {
            delay(USER_NOTICE_MILLIS)
            spelled = false
        }
    }
    AnimatedVisibility(
        visible = shown != RemoteController.IDLE,
        modifier = modifier,
        enter = slideInVertically(tween(150)) { -it } + fadeIn(tween(150)),
        exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(150)),
    ) {
        // A floating pill over the screen: showing or hiding it never moves the picture underneath.
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, shadowElevation = 3.dp) {
            val user = visibleController == RemoteController.USER
            Row(
                modifier = Modifier.animateContentSize(tween(150))
                    .padding(start = if (user && !spelled) 4.dp else 16.dp, end = if (user) 4.dp else 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visibleController == RemoteController.PARTNER) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                if (!user || spelled) Text(
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
