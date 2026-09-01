package me.ayuilos.miffan.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01

@Composable
fun ChatMessageBranchSelector(
    selectedIndex: Int,
    branchCount: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (branchCount > 1) {
            val actionColor = MaterialTheme.colorScheme.onSurfaceVariant

            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = "Prev",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (selectedIndex == 0) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (selectedIndex > 0) {
                                onSelect(selectedIndex - 1)
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )

            Text(
                text = "${selectedIndex + 1}/$branchCount",
                style = MaterialTheme.typography.bodySmall,
                color = actionColor
            )

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = "Next",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (selectedIndex == branchCount - 1) 0.5f else 1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (selectedIndex < branchCount - 1) {
                                onSelect(selectedIndex + 1)
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = actionColor
            )
        }
    }
}
