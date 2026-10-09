package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign

/** Enlarges the picture on the phone only; the computer's own zoom is a pinch. */
@Composable
internal fun RemoteScreenZoomControls(
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shape = RoundedCornerShape(50),
        shadowElevation = 3.dp,
    ) {
        Column {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(44.dp)) {
                Icon(HugeIcons.PlusSign, stringResource(R.string.workspace_screen_view_zoom_in), Modifier.size(20.dp))
            }
            IconButton(onClick = onZoomOut, enabled = canZoomOut, modifier = Modifier.size(44.dp)) {
                Icon(HugeIcons.MinusSign, stringResource(R.string.workspace_screen_view_zoom_out), Modifier.size(20.dp))
            }
        }
    }
}
