package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import me.ayuilos.miffan.data.ai.computer.RemoteController

@Composable
internal fun RemoteScreenControllerBanner(controller: RemoteController, onHandBack: () -> Unit) {
    // Keep the last visible message during the exit transition.
    var visibleController by remember { mutableStateOf(controller) }
    if (controller != RemoteController.IDLE && visibleController != controller) visibleController = controller
    AnimatedVisibility(
        visible = controller != RemoteController.IDLE,
        enter = expandVertically(tween(150)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(150)) + fadeOut(tween(150)),
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (visibleController == RemoteController.PARTNER) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    stringResource(if (visibleController == RemoteController.PARTNER) {
                        R.string.workspace_screen_partner_operating
                    } else R.string.workspace_screen_user_operating),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (visibleController == RemoteController.USER) {
                    TextButton(onClick = onHandBack, enabled = controller == RemoteController.USER) {
                        Text(stringResource(R.string.workspace_screen_hand_back))
                    }
                }
            }
        }
    }
}
