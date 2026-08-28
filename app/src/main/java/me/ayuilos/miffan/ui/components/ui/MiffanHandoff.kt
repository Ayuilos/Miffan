package me.ayuilos.miffan.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

enum class MiffanHandoffDestination { EmptyChat, WaitingReply }

@Stable
class MiffanHandoffState {
    internal var emptyBounds by mutableStateOf<Rect?>(null)
    internal var waitingBounds by mutableStateOf<Rect?>(null)

    internal fun update(destination: MiffanHandoffDestination, bounds: Rect?) {
        when (destination) {
            MiffanHandoffDestination.EmptyChat -> emptyBounds = bounds
            MiffanHandoffDestination.WaitingReply -> waitingBounds = bounds
        }
    }
}

/** Layout slots contain no mascot, so moving between them cannot restart the face or gaze. */
@Composable
fun MiffanHandoffAnchor(
    state: MiffanHandoffState,
    destination: MiffanHandoffDestination,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(state, destination) {
        onDispose { state.update(destination, null) }
    }
    Spacer(modifier.onGloballyPositioned {
        state.update(destination, Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
    })
}

/** One persistent content instance, independent of lazy item lifetime and input layout. */
@Composable
fun MiffanHandoff(
    state: MiffanHandoffState,
    destination: MiffanHandoffDestination?,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    content: @Composable (Modifier, Boolean) -> Unit,
) {
    val reduced = reducedMotion || rememberMiffanReducedMotion()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var lastBounds by remember { mutableStateOf<Rect?>(null) }
    val measured = when (destination) {
        MiffanHandoffDestination.EmptyChat -> state.emptyBounds
        MiffanHandoffDestination.WaitingReply -> state.waitingBounds
        null -> null
    }?.translate(-origin)
    val target = measured ?: lastBounds
    SideEffect { if (measured != null) lastBounds = measured }
    val visible = measured?.overlaps(Rect(Offset.Zero, viewport)) == true
    val opacity = animateFloatAsState(
        if (visible) 1f else 0f,
        tween(if (reduced) 0 else 160), label = "miffan_handoff_visibility",
    )
    Box(modifier.clipToBounds().onGloballyPositioned {
        origin = it.positionInRoot()
        viewport = Size(it.size.width.toFloat(), it.size.height.toFloat())
    }) {
        if (target != null) {
            val bounds = remember { Animatable(target, Rect.VectorConverter) }
            var settledDestination by remember { mutableStateOf(destination) }
            LaunchedEffect(target, destination, reduced, measured != null) {
                if (measured == null) return@LaunchedEffect
                if (reduced || destination == settledDestination || destination == null) {
                    bounds.snapTo(target)
                } else {
                    bounds.animateTo(target, spring(dampingRatio = 1f, stiffness = 380f))
                }
                settledDestination = destination
            }
            val baseSize = with(LocalDensity.current) { 168.dp.toPx() }
            content(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(168.dp)
                    .graphicsLayer {
                        val rect = bounds.value
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = rect.left
                        translationY = rect.top
                        scaleX = rect.width / baseSize
                        scaleY = rect.height / baseSize
                        alpha = opacity.value
                    }
                    .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
                visible,
            )
        }
    }
}
