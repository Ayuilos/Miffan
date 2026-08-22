package me.ayuilos.miffan.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ayuilos.miffan.ui.context.LocalSettings
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class MiffanMascotState {
    Idle,
    Thinking,
    Happy,
    Error,
}

private enum class MiffanIdleGesture {
    None,
    Yawn,
    RiceBounce,
    Doze,
}

private data class IdleGazeSpec(
    val delayRange: LongRange,
    val horizontalRange: IntRange,
    val verticalRange: IntRange,
    val duration: Int,
)

/**
 * The in-app form of the launcher icon. Its pieces are drawn separately so the
 * bowl can breathe while the face and rice react to the current chat state.
 */
@Composable
fun MiffanMascot(
    state: MiffanMascotState,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    attentionTarget: Offset? = null,
    attentionId: Int = 0,
    dayPhase: MiffanDayPhase = MiffanDayPhase.Noon,
    previewIdleGestures: Boolean = false,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "miffan_mascot")
    val breathDuration = when (dayPhase) {
        MiffanDayPhase.Morning -> 2_600
        MiffanDayPhase.Noon -> 2_100
        MiffanDayPhase.Night -> 3_000
    }
    val breath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(breathDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "miffan_breath",
    )
    val thinkingPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
        ),
        label = "miffan_thinking",
    )

    val blink = remember { Animatable(1f) }
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    val pokeOffset = remember { Animatable(0f) }
    val pokeSquash = remember { Animatable(1f) }
    val pokeExpression = remember { Animatable(0f) }
    val tapGazeX = remember { Animatable(0f) }
    val tapGazeY = remember { Animatable(0f) }
    val idleGestureProgress = remember { Animatable(0f) }
    var idleGesture by remember { mutableStateOf(MiffanIdleGesture.None) }
    var tapTargetX by remember { mutableFloatStateOf(0f) }
    var tapTargetY by remember { mutableFloatStateOf(0f) }
    var pokeCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, dayPhase) {
        blink.snapTo(1f)
        while (currentCoroutineContext().isActive) {
            val delayRange = if (state == MiffanMascotState.Idle) {
                when (dayPhase) {
                    MiffanDayPhase.Morning -> 2_400L..5_400L
                    MiffanDayPhase.Noon -> 1_800L..4_400L
                    MiffanDayPhase.Night -> 3_000L..6_200L
                }
            } else {
                1_900L..4_600L
            }
            val closeDuration = if (state == MiffanMascotState.Idle && dayPhase == MiffanDayPhase.Night) 110 else 70
            delay(Random.nextLong(delayRange.first, delayRange.last + 1))
            blink.animateTo(0.08f, tween(closeDuration))
            blink.animateTo(1f, tween(closeDuration + 50))
            if (Random.nextFloat() < 0.22f) {
                delay(110)
                blink.animateTo(0.08f, tween(60))
                blink.animateTo(1f, tween(100))
            }
        }
    }

    LaunchedEffect(state, dayPhase) {
        if (state != MiffanMascotState.Idle) {
            gazeX.animateTo(0f, tween(180))
            gazeY.animateTo(0f, tween(180))
            return@LaunchedEffect
        }
        while (currentCoroutineContext().isActive) {
            val (delayRange, horizontalRange, verticalRange, duration) = when (dayPhase) {
                MiffanDayPhase.Morning -> IdleGazeSpec(1_800L..4_000L, -3..3, -1..2, 420)
                MiffanDayPhase.Noon -> IdleGazeSpec(1_100L..3_000L, -4..4, -2..2, 280)
                MiffanDayPhase.Night -> IdleGazeSpec(2_600L..5_200L, -2..2, -1..1, 520)
            }
            delay(Random.nextLong(delayRange.first, delayRange.last + 1))
            gazeX.animateTo(Random.nextInt(horizontalRange.first, horizontalRange.last + 1).toFloat(), tween(duration))
            gazeY.animateTo(Random.nextInt(verticalRange.first, verticalRange.last + 1).toFloat(), tween(duration))
        }
    }

    LaunchedEffect(state, dayPhase, previewIdleGestures) {
        idleGestureProgress.snapTo(0f)
        idleGesture = MiffanIdleGesture.None
        if (state != MiffanMascotState.Idle) return@LaunchedEffect

        delay(if (previewIdleGestures) 700L else Random.nextLong(6_000L, 12_000L))
        while (currentCoroutineContext().isActive) {
            idleGesture = when (dayPhase) {
                MiffanDayPhase.Morning -> MiffanIdleGesture.Yawn
                MiffanDayPhase.Noon -> MiffanIdleGesture.RiceBounce
                MiffanDayPhase.Night -> MiffanIdleGesture.Doze
            }
            when (idleGesture) {
                MiffanIdleGesture.Yawn -> {
                    idleGestureProgress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                    delay(420)
                    idleGestureProgress.animateTo(0f, tween(720, easing = FastOutSlowInEasing))
                }

                MiffanIdleGesture.RiceBounce -> {
                    idleGestureProgress.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
                    idleGestureProgress.snapTo(0f)
                }

                MiffanIdleGesture.Doze -> {
                    idleGestureProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
                    delay(1_250)
                    idleGestureProgress.animateTo(0f, tween(780, easing = FastOutSlowInEasing))
                }

                MiffanIdleGesture.None -> Unit
            }
            idleGesture = MiffanIdleGesture.None
            delay(
                if (previewIdleGestures) {
                    1_400L
                } else {
                    when (dayPhase) {
                        MiffanDayPhase.Morning -> Random.nextLong(22_000L, 38_000L)
                        MiffanDayPhase.Noon -> Random.nextLong(16_000L, 30_000L)
                        MiffanDayPhase.Night -> Random.nextLong(26_000L, 46_000L)
                    }
                }
            )
        }
    }

    LaunchedEffect(attentionId) {
        val target = attentionTarget ?: return@LaunchedEffect
        tapTargetX = target.x.coerceIn(-1f, 1f) * 5.5f
        tapTargetY = target.y.coerceIn(-1f, 1f) * 3.5f
        pokeCount++
    }

    LaunchedEffect(pokeCount) {
        if (pokeCount == 0) return@LaunchedEffect
        coroutineScope {
            launch {
                pokeExpression.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                delay(520)
                pokeExpression.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            }
            launch {
                pokeSquash.animateTo(0.985f, tween(120, easing = FastOutSlowInEasing))
                pokeSquash.animateTo(1.004f, tween(200, easing = FastOutSlowInEasing))
                pokeSquash.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            }
            launch {
                pokeOffset.animateTo(0.6f, tween(120, easing = FastOutSlowInEasing))
                pokeOffset.animateTo(-0.6f, tween(200, easing = FastOutSlowInEasing))
                pokeOffset.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            }
            launch {
                coroutineScope {
                    launch { tapGazeX.animateTo(tapTargetX, tween(260, easing = FastOutSlowInEasing)) }
                    launch { tapGazeY.animateTo(tapTargetY, tween(260, easing = FastOutSlowInEasing)) }
                }
                delay(500)
                coroutineScope {
                    launch { tapGazeX.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
                    launch { tapGazeY.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
                }
            }
        }
    }

    val stateRotation by animateFloatAsState(
        targetValue = if (state == MiffanMascotState.Error) -6f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "miffan_state_rotation",
    )
    val stateOffsetY by animateFloatAsState(
        targetValue = when (state) {
            MiffanMascotState.Happy -> -5f
            MiffanMascotState.Error -> 6f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow),
        label = "miffan_state_offset",
    )

    val interactiveModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectTapGestures { position ->
                if (size.width > 0 && size.height > 0) {
                    tapTargetX = ((position.x / size.width - 0.5f) * 11f).coerceIn(-5.5f, 5.5f)
                    tapTargetY = ((position.y / size.height - 0.5f) * 7f).coerceIn(-3.5f, 3.5f)
                }
                pokeCount++
            }
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .then(interactiveModifier)
            .semantics {
                contentDescription = "Miffan mascot"
                if (interactive) {
                    role = Role.Button
                    onClick(label = "Poke Miffan") {
                        tapTargetX = 0f
                        tapTargetY = -2f
                        pokeCount++
                        true
                    }
                }
            },
    ) {
        val unit = size.minDimension / 200f
        val left = (size.width - size.minDimension) / 2f
        val top = (size.height - size.minDimension) / 2f
        val rawGestureProgress = if (state == MiffanMascotState.Idle) {
            idleGestureProgress.value
        } else {
            0f
        }
        val gestureVisibility = 1f - pokeExpression.value
        val yawnAmount = if (idleGesture == MiffanIdleGesture.Yawn) {
            rawGestureProgress * gestureVisibility
        } else {
            0f
        }
        val dozeAmount = if (idleGesture == MiffanIdleGesture.Doze) {
            rawGestureProgress * gestureVisibility
        } else {
            0f
        }
        val riceBounceAmount = if (idleGesture == MiffanIdleGesture.RiceBounce) {
            sin(rawGestureProgress * Math.PI).toFloat().coerceAtLeast(0f) * gestureVisibility
        } else {
            0f
        }
        val bodyBob = when (state) {
            MiffanMascotState.Thinking -> sin(Math.toRadians(thinkingPhase.toDouble())).toFloat() * 2.2f
            MiffanMascotState.Happy -> -breath * 3f
            else -> {
                val breathAmplitude = when (dayPhase) {
                    MiffanDayPhase.Morning -> 1.3f
                    MiffanDayPhase.Noon -> 1.6f
                    MiffanDayPhase.Night -> 1f
                }
                -breath * breathAmplitude - riceBounceAmount * 0.8f + dozeAmount * 1.5f
            }
        }
        val bodyScaleY = (1f + breath * 0.018f + yawnAmount * 0.025f) * pokeSquash.value
        val bodyScaleX = (1f - breath * 0.008f - yawnAmount * 0.01f) * (2f - pokeSquash.value)
        val thinkingRadians = Math.toRadians(thinkingPhase.toDouble())
        val ambientLookX = when (state) {
            MiffanMascotState.Thinking -> sin(thinkingRadians).toFloat() * 4.5f
            MiffanMascotState.Error -> -2f
            else -> gazeX.value
        }
        val ambientLookY = when (state) {
            MiffanMascotState.Thinking -> cos(thinkingRadians * 0.7).toFloat() * 1.8f
            MiffanMascotState.Error -> 2f
            else -> gazeY.value
        }
        val lookX = ambientLookX * (1f - pokeExpression.value) + tapGazeX.value
        val lookY = ambientLookY * (1f - pokeExpression.value) + tapGazeY.value

        withTransform({
            translate(left, top)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            drawOval(
                color = Color.Black.copy(alpha = 0.10f),
                topLeft = Offset(49f, 169f + stateOffsetY),
                size = Size(102f, 11f - bodyBob.coerceAtMost(2f)),
            )

            withTransform({
                translate(0f, bodyBob + stateOffsetY + pokeOffset.value)
                rotate(stateRotation + dozeAmount * 4f, pivot = Offset(100f, 112f))
                scale(bodyScaleX, bodyScaleY, pivot = Offset(100f, 112f))
            }) {
                drawMascotBody(
                    state = state,
                    eyeScaleY = blink.value * (1f - yawnAmount * 0.78f - dozeAmount * 0.92f),
                    lookX = lookX,
                    lookY = lookY,
                    thinkingPhase = thinkingPhase,
                    reactionProgress = pokeExpression.value,
                    idleGesture = idleGesture,
                    idleGestureProgress = rawGestureProgress,
                    idleGestureVisibility = gestureVisibility,
                )
            }
        }
    }
}

@Composable
fun MiffanMascotLoadingIndicator(modifier: Modifier = Modifier) {
    if (LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator) {
        MiffanMascot(
            state = MiffanMascotState.Thinking,
            modifier = modifier,
        )
    } else {
        ContainedLoadingIndicator(modifier = modifier)
    }
}

private fun DrawScope.drawMascotBody(
    state: MiffanMascotState,
    eyeScaleY: Float,
    lookX: Float,
    lookY: Float,
    thinkingPhase: Float,
    reactionProgress: Float,
    idleGesture: MiffanIdleGesture,
    idleGestureProgress: Float,
    idleGestureVisibility: Float,
) {
    val bowl = Path().apply {
        moveTo(24f, 67f)
        cubicTo(24f, 52f, 58f, 43f, 100f, 43f)
        cubicTo(142f, 43f, 176f, 52f, 176f, 67f)
        lineTo(173f, 112f)
        cubicTo(168f, 149f, 140f, 170f, 100f, 170f)
        cubicTo(60f, 170f, 32f, 149f, 27f, 112f)
        close()
    }
    drawPath(bowl, Color(0xFFC76644))

    val rim = Path().apply {
        moveTo(24f, 67f)
        cubicTo(24f, 52f, 58f, 43f, 100f, 43f)
        cubicTo(142f, 43f, 176f, 52f, 176f, 67f)
        cubicTo(176f, 82f, 142f, 93f, 100f, 93f)
        cubicTo(58f, 93f, 24f, 82f, 24f, 67f)
        close()
    }
    drawPath(rim, Color(0xFFD6724F))

    val rice = Path().apply {
        moveTo(38f, 69f)
        cubicTo(42f, 62f, 53f, 58f, 66f, 58f)
        cubicTo(73f, 50f, 86f, 48f, 98f, 51f)
        cubicTo(110f, 43f, 128f, 44f, 137f, 52f)
        cubicTo(150f, 52f, 160f, 58f, 162f, 66f)
        cubicTo(156f, 76f, 131f, 82f, 101f, 83f)
        cubicTo(72f, 84f, 47f, 79f, 38f, 72f)
        close()
    }
    val riceHighlight = Path().apply {
        moveTo(54f, 66f)
        cubicTo(68f, 56f, 91f, 53f, 113f, 55f)
        cubicTo(101f, 61f, 80f, 67f, 54f, 69f)
        close()
    }
    val riceBounce = if (idleGesture == MiffanIdleGesture.RiceBounce) {
        sin(idleGestureProgress * Math.PI).toFloat().coerceAtLeast(0f) * idleGestureVisibility
    } else {
        0f
    }
    withTransform({
        translate(0f, -riceBounce * 1.8f)
        scale(1f + riceBounce * 0.012f, 1f, pivot = Offset(100f, 69f))
    }) {
        drawPath(rice, Color(0xFFFFE8A9))
        drawPath(riceHighlight, Color.White.copy(alpha = 0.24f))
    }

    if (riceBounce > 0f) {
        val grainCenter = Offset(116f + idleGestureProgress * 5f, 51f - riceBounce * 13f)
        rotate(-18f + idleGestureProgress * 42f, pivot = grainCenter) {
            drawRoundRect(
                color = Color(0xFFFFE8A9).copy(alpha = riceBounce),
                topLeft = Offset(grainCenter.x - 4.5f, grainCenter.y - 2.2f),
                size = Size(9f, 4.4f),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }

    if (state == MiffanMascotState.Thinking) {
        val radians = Math.toRadians(thinkingPhase.toDouble())
        val grainX = 100f + cos(radians).toFloat() * 41f
        val grainY = 40f + sin(radians).toFloat() * 8f
        rotate(thinkingPhase + 18f, pivot = Offset(grainX, grainY)) {
            drawRoundRect(
                color = Color(0xFFFFE8A9),
                topLeft = Offset(grainX - 5f, grainY - 2.4f),
                size = Size(10f, 4.8f),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }

    val eyeColor = Color(0xFFFFE8A9)
    val reaction = reactionProgress.coerceIn(0f, 1f)
    val restingMouthColor = eyeColor.copy(alpha = 1f - reaction)
    val eyeHeight = 14f * eyeScaleY.coerceAtLeast(0.08f)
    listOf(70f, 130f).forEach { eyeCenterX ->
        drawRoundRect(
            color = eyeColor,
            topLeft = Offset(
                x = eyeCenterX - 4.5f + lookX,
                y = 111f - eyeHeight / 2f + lookY,
            ),
            size = Size(9f, eyeHeight),
            cornerRadius = CornerRadius(5f, 5f),
        )
    }

    when (state) {
        MiffanMascotState.Happy -> {
            drawArc(
                color = restingMouthColor,
                startAngle = 12f,
                sweepAngle = 156f,
                useCenter = false,
                topLeft = Offset(89f, 119f),
                size = Size(22f, 16f),
                style = Stroke(width = 5f),
            )
        }

        MiffanMascotState.Error -> {
            drawArc(
                color = restingMouthColor,
                startAngle = 194f,
                sweepAngle = 152f,
                useCenter = false,
                topLeft = Offset(91f, 130f),
                size = Size(18f, 12f),
                style = Stroke(width = 4f),
            )
        }

        MiffanMascotState.Thinking -> {
            drawOval(
                color = restingMouthColor,
                topLeft = Offset(94f, 124f),
                size = Size(12f, 15f),
            )
        }

        MiffanMascotState.Idle -> {
            val yawn = if (idleGesture == MiffanIdleGesture.Yawn) {
                idleGestureProgress * idleGestureVisibility
            } else {
                0f
            }
            val mouthWidth = 10f + 7f * reaction + 3f * yawn
            val mouthHeight = 8f + 5f * reaction + 11f * yawn
            drawOval(
                color = eyeColor,
                topLeft = Offset(100f - mouthWidth / 2f, 131f - yawn - mouthHeight / 2f),
                size = Size(mouthWidth, mouthHeight),
            )
        }
    }

    if (reaction > 0f && state != MiffanMascotState.Idle) {
        drawOval(
            color = eyeColor.copy(alpha = reaction),
            topLeft = Offset(91.5f, 124.5f),
            size = Size(17f, 13f),
        )
    }
}
