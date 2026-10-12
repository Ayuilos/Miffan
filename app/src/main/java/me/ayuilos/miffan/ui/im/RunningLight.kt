package me.ayuilos.miffan.ui.im

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R

private val RunningGreenLight = Color(0xFF34C759)
private val RunningGreenDark = Color(0xFF30D158)

/** Where a light centred on the circle's edge at 45° sits, as a fraction of the avatar's size. */
private const val EDGE_AT_45 = 0.854f

/**
 * A partner at work: a green light on the avatar's lower corner, with a slow beacon spreading from it,
 * so the state reads at a glance and without relying on the mascot's own animation. Place it in a
 * Box exactly the avatar's size; [ring] should match what is behind the avatar.
 */
@Composable
internal fun BoxScope.RunningLight(running: Boolean, avatarSize: Dp, ring: Color = MaterialTheme.colorScheme.background) {
    val light = (avatarSize * 0.24f).coerceIn(10.dp, 22.dp)
    val green = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) RunningGreenDark else RunningGreenLight
    val description = stringResource(R.string.im_partner_running)
    AnimatedVisibility(
        visible = running,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = Modifier.align(Alignment.TopStart)
            .offset(avatarSize * EDGE_AT_45 - light / 2, avatarSize * EDGE_AT_45 - light / 2),
    ) {
        val beacon by rememberInfiniteTransition(label = "running-light").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "running-beacon",
        )
        Box(
            Modifier.size(light)
                .semantics { contentDescription = description }
                .drawBehind {
                    // The beacon grows out from under the light and fades as it spreads.
                    drawCircle(green.copy(alpha = 0.45f * (1f - beacon)), radius = size.minDimension / 2 * (1f + 1.2f * beacon))
                }
                .background(green, CircleShape)
                .border((light * 0.16f).coerceAtLeast(2.dp), ring, CircleShape),
        )
    }
}
