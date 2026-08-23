package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanMotionProfile

@Composable
fun MiffanMotionProfileEditor(
    appearance: MiffanAppearance,
    motionProfile: MiffanMotionProfile,
    onMotionProfileChange: (MiffanMotionProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "动作性格",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = MiffanMotionProfile.entries,
                key = { it.name },
            ) { profile ->
                val selected = motionProfile == profile
                Surface(
                    onClick = { onMotionProfileChange(profile) },
                    shape = MaterialTheme.shapes.large,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .width(104.dp)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        MiffanMascot(
                            state = MiffanMascotState.Idle,
                            appearance = appearance,
                            motionProfile = profile,
                            previewIdleGestures = true,
                            modifier = Modifier.size(52.dp),
                        )
                        Text(
                            text = profile.displayName,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            minLines = 2,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

val MiffanMotionProfile.displayName: String
    get() = when (this) {
        MiffanMotionProfile.LIVELY -> "活泼"
        MiffanMotionProfile.CALM -> "安静"
        MiffanMotionProfile.CURIOUS -> "好奇"
    }

val MiffanMotionProfile.description: String
    get() = when (this) {
        MiffanMotionProfile.LIVELY -> "轻快、有弹性"
        MiffanMotionProfile.CALM -> "缓慢、克制"
        MiffanMotionProfile.CURIOUS -> "目光先行"
    }
