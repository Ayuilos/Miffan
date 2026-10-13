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
private val WaitingAmberLight = Color(0xFFFF9500)
private val WaitingAmberDark = Color(0xFFFF9F0A)

/** Where a light centred on the circle's edge at 45° sits, as a fraction of the avatar's size. */
private const val EDGE_AT_45 = 0.854f

/**
 * What a partner is doing, as a light on the avatar's lower corner that reads at a glance without
 * relying on the mascot's own animation: green with a slow beacon while it works, amber and blinking
 * while a request of its waits for the owner (which wins, since only the owner can move it on).
 * Place it in a Box exactly the avatar's size; [ring] should match what is behind the avatar.
 */
@Composable
internal fun BoxScope.RunningLight(
    running: Boolean,
    avatarSize: Dp,
    ring: Color = MaterialTheme.colorScheme.background,
    waiting: Boolean = false,
) {
    val light = (avatarSize * 0.26f).coerceIn(12.dp, 22.dp)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val description = stringResource(if (waiting) R.string.im_partner_waiting else R.string.im_partner_running)
    AnimatedVisibility(
        visible = running || waiting,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = Modifier.align(Alignment.TopStart)
            .offset(avatarSize * EDGE_AT_45 - light / 2, avatarSize * EDGE_AT_45 - light / 2),
    ) {
        val color = when {
            waiting -> if (dark) WaitingAmberDark else WaitingAmberLight
            else -> if (dark) RunningGreenDark else RunningGreenLight
        }
        val transition = rememberInfiniteTransition(label = "partner-light")
        val beacon by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "running-beacon",
        )
        val blink by transition.animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "waiting-blink",
        )
        Box(
            Modifier.size(light)
                .semantics { contentDescription = description }
                .drawBehind {
                    // Working: a beacon grows out from under the light and fades as it spreads.
                    if (!waiting) drawCircle(color.copy(alpha = 0.45f * (1f - beacon)), radius = size.minDimension / 2 * (1f + 1.2f * beacon))
                }
                .background(ring, CircleShape)
                .drawBehind {
                    val inset = (light * 0.12f).coerceAtLeast(1.5.dp).toPx()
                    drawCircle(color.copy(alpha = if (waiting) blink else 1f), radius = size.minDimension / 2 - inset)
                },
        )
    }
}
