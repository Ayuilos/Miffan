package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.ayuilos.miffan.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Touch01

/** How long the "tap again to take over" prompt waits for the second tap. */
internal const val TAKEOVER_ARM_MILLIS = 3_000L

/**
 * The spot an action awaiting the user's yes would act on, as a share of the picture's width and height. The partner's
 * moving pointer needs no drawing here: the computer's own driver already shows it on the desktop.
 */
internal data class RemoteScreenMarker(val x: Float, val y: Float, val label: String)

/** Colors the canvas needs for [RemoteScreenMarker]; read from the theme outside the draw scope. */
internal data class RemoteScreenMarkerStyle(val accent: Color, val onAccent: Color, val textMeasurer: TextMeasurer)

/** Dims the picture except a circle around the spot, rings it, and labels it. */
internal fun DrawScope.drawApprovalMarker(tip: Offset, marker: RemoteScreenMarker, style: RemoteScreenMarkerStyle) {
    val label = style.textMeasurer.measure(marker.label, TextStyle(color = style.onAccent, fontSize = 11.sp))
    val padX = 7.dp.toPx()
    val padY = 1.5.dp.toPx()
    val radius = 26.dp.toPx()
    val scrim = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addOval(Rect(tip, radius))
    }
    drawPath(scrim, Color.Black.copy(alpha = 0.28f))
    drawCircle(style.accent.copy(alpha = 0.22f), radius + 4.dp.toPx(), tip, style = Stroke(4.dp.toPx()))
    drawCircle(style.accent, radius, tip, style = Stroke(2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    val at = Offset(tip.x - label.size.width / 2f - padX, tip.y - radius - label.size.height - padY * 2 - 6.dp.toPx())
    drawRoundRect(style.accent, at, Size(label.size.width + padX * 2, label.size.height + padY * 2), CornerRadius(8.dp.toPx()))
    drawText(label, topLeft = at + Offset(padX, padY))
}

/**
 * The first touch on a screen the partner is using only asks; a second one within [TAKEOVER_ARM_MILLIS] takes over.
 * A stray touch therefore never interrupts the partner.
 */
@Composable
internal fun BoxScope.RemoteScreenTakeoverPrompt(armed: Boolean, partnerName: String) {
    AnimatedVisibility(armed, Modifier.matchParentSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Box(Modifier.background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            val progress = remember { Animatable(1f) }
            LaunchedEffect(Unit) { progress.animateTo(0f, tween(TAKEOVER_ARM_MILLIS.toInt(), easing = LinearEasing)) }
            Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp,
                modifier = Modifier.padding(24.dp).widthIn(min = 230.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(HugeIcons.Touch01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.workspace_screen_takeover_again, partnerName), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.workspace_screen_takeover_detail, partnerName),
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { progress.value }, Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {},
                        gapSize = 0.dp)
                }
            }
        }
    }
}

/** The step the partner is on, over the bottom of the picture while it works. */
@Composable
internal fun RemoteScreenCaption(text: String?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(text) }
    if (text != null && text != shown) shown = text
    AnimatedVisibility(text != null, modifier, enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { it / 2 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 }) {
        Surface(color = Color(0xB8111317), contentColor = Color.White, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CircularProgressIndicator(Modifier.size(13.dp), color = MaterialTheme.colorScheme.tertiaryContainer, strokeWidth = 2.dp)
                Text(shown.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** While the user holds a desktop the partner was using: who drives now, and the way to give it back. */
@Composable
internal fun BoxScope.RemoteScreenUserHold(visible: Boolean, partnerName: String?, onHandBack: () -> Unit,
    avatar: (@Composable (Modifier) -> Unit)?) {
    AnimatedVisibility(visible, Modifier.align(Alignment.TopStart).padding(10.dp), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
            Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(HugeIcons.Touch01, null, Modifier.size(14.dp))
                Text(stringResource(R.string.workspace_screen_you_operate), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.9f), exit = fadeOut(tween(150))) {
        Surface(onClick = onHandBack, enabled = visible, shape = CircleShape, shadowElevation = 4.dp) {
            Row(Modifier.height(40.dp).padding(start = if (avatar != null) 6.dp else 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                avatar?.invoke(Modifier.size(28.dp))
                Text(partnerName?.let { stringResource(R.string.workspace_screen_hand_back_to, it) }
                    ?: stringResource(R.string.workspace_screen_hand_back), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** Keeps the takeover prompt up for [TAKEOVER_ARM_MILLIS] after the first touch, and drops it when the partner stops. */
@Composable
internal fun rememberTakeoverArming(partnerActive: Boolean): androidx.compose.runtime.MutableState<Boolean> {
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(armed.value) {
        if (armed.value) {
            delay(TAKEOVER_ARM_MILLIS)
            armed.value = false
        }
    }
    LaunchedEffect(partnerActive) { if (!partnerActive) armed.value = false }
    return armed
}
