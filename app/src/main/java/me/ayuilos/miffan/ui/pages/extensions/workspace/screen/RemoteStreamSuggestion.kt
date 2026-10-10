package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

/**
 * Offers high-performance mode on a plain connection when the computer's Sunshine runs and could
 * be used: one "Enable" walks through the encryption change and pairing, then reconnects.
 * Dismissing it hides it for this computer for good; the settings keep the full switch.
 */
@Composable
internal fun RemoteStreamSuggestion(vm: RemoteScreenVM, modifier: Modifier = Modifier) {
    val suggestion by vm.streamSuggestion.collectAsStateWithLifecycle()
    val setups by vm.streamSetups.collectAsStateWithLifecycle()
    val state = suggestion?.let { current -> setups[current.hostId]?.takeIf { it.connectionRevision == current.revision } }
    var enabling by remember(suggestion) { mutableStateOf(false) }
    // Only a running Sunshine with the mode still off is worth interrupting the user for.
    val offer = state?.status?.let { it.installed && it.running } == true && state.enabled != true
    AnimatedVisibility(
        visible = offer || enabling,
        modifier = modifier,
        enter = slideInVertically(tween(150)) { -it } + fadeIn(tween(150)),
        exit = slideOutVertically(tween(150)) { -it } + fadeOut(tween(150)),
    ) {
        // Floats over the screen like the controller pill, so it never moves the picture.
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(24.dp), shadowElevation = 3.dp,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.workspace_screen_stream_suggest), Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge)
                TextButton(enabled = !enabling, onClick = { enabling = true }) { Text(stringResource(R.string.im_computer_stream_enable)) }
                IconButton(enabled = !enabling, onClick = vm::dismissStreamSuggestion) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.workspace_screen_stream_suggest_dismiss), Modifier.size(18.dp))
                }
            }
        }
    }
    if (enabling && state != null) {
        RemoteStreamEnableFlow(state, onEnforce = { vm.enforceStreamEncryption() }, onPair = { vm.pairStream() },
            onCancelPairing = vm::cancelStreamPairing, onEnable = { vm.enableStream() },
            onFinished = { enabled -> enabling = false; vm.finishStreamSuggestion(enabled) })
    }
}
