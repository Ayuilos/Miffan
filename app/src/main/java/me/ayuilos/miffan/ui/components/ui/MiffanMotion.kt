package me.ayuilos.miffan.ui.components.ui

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

enum class MiffanPresentation { Scene, Avatar }

internal fun miffanRunsAmbientMotion(
    presentation: MiffanPresentation,
    state: MiffanMascotState,
    reducedMotion: Boolean,
): Boolean = !reducedMotion && (presentation == MiffanPresentation.Scene || state != MiffanMascotState.Idle)

internal fun MiffanMotionTuning.forAvatar(): MiffanMotionTuning = copy(
    breathAmplitude = breathAmplitude * 0.35f,
    gazeAmplitude = gazeAmplitude * 0.6f,
    thinkingBob = thinkingBob * 0.45f,
    happyBob = happyBob * 0.45f,
    stateAmplitude = stateAmplitude * 0.5f,
)

/** Settle from the current phase, then stop frame clock work for historical avatars. */
@Composable
internal fun rememberMiffanCycle(
    enabled: Boolean,
    durationMillis: Int,
    peak: Float = 1f,
    reverse: Boolean = false,
    label: String,
): State<Float> {
    val phase = remember { Animatable(0f, Float.VectorConverter, label = label) }
    LaunchedEffect(enabled, durationMillis, peak, reverse) {
        if (!enabled) {
            phase.animateTo(0f, tween(180))
            return@LaunchedEffect
        }
        val easing = if (reverse) FastOutSlowInEasing else LinearEasing
        while (currentCoroutineContext().isActive) {
            val remaining = ((1f - phase.value / peak) * durationMillis).roundToInt().coerceAtLeast(1)
            phase.animateTo(peak, tween(remaining, easing = easing))
            if (reverse) phase.animateTo(0f, tween(durationMillis, easing = easing))
            else phase.snapTo(0f)
        }
    }
    return phase.asState()
}

/** Renderer-only values. The same mouth contour is used for every expression. */
@Immutable
internal data class MiffanFacePose(
    val eyeOpen: Float = 1f,
    val eyeAsymmetry: Float = 0f,
    val mouthOpen: Float = 8f,
    val mouthCurve: Float = 0f,
) {
    fun blendTo(other: MiffanFacePose, amount: Float): MiffanFacePose {
        val t = amount.coerceIn(0f, 1f)
        if (t == 0f) return this
        if (t == 1f) return other
        return MiffanFacePose(
            eyeOpen + (other.eyeOpen - eyeOpen) * t,
            eyeAsymmetry + (other.eyeAsymmetry - eyeAsymmetry) * t,
            mouthOpen + (other.mouthOpen - mouthOpen) * t,
            mouthCurve + (other.mouthCurve - mouthCurve) * t,
        )
    }

    companion object {
        val Attention = MiffanFacePose(1.14f, 0.06f, 13f, 0.12f)
        val VectorConverter = TwoWayConverter<MiffanFacePose, AnimationVector4D>(
            convertToVector = { AnimationVector4D(it.eyeOpen, it.eyeAsymmetry, it.mouthOpen, it.mouthCurve) },
            convertFromVector = { MiffanFacePose(it.v1, it.v2, it.v3, it.v4) },
        )
    }
}

internal fun miffanFacePose(
    state: MiffanMascotState,
    inputState: MiffanMascotInputState,
): MiffanFacePose = when (state) {
    MiffanMascotState.Thinking -> MiffanFacePose(0.9f, 0.1f, 13f, 0f)
    MiffanMascotState.Happy -> MiffanFacePose(0.7f, 0f, 4f, 1f)
    MiffanMascotState.Error -> MiffanFacePose(0.76f, -0.04f, 4f, -0.8f)
    MiffanMascotState.UpdateAvailable -> MiffanFacePose(0.98f, 0.04f, 4f, 0.7f)
    MiffanMascotState.Idle -> when (inputState) {
        MiffanMascotInputState.Inactive -> MiffanFacePose()
        MiffanMascotInputState.Focused -> MiffanFacePose(1.1f, 0.05f, 6f, 0.12f)
        MiffanMascotInputState.Typing -> MiffanFacePose(1.04f, 0.02f, 5f, 0.22f)
    }
}

internal fun <T> MiffanMotionTuning.springSpec(
    stiffness: Float,
    dampingRatio: Float = 1f,
): SpringSpec<T> = spring(
    stiffness = stiffness / (durationScale * durationScale),
    dampingRatio = dampingRatio,
)

@Composable
internal fun animateMiffanFace(
    state: MiffanMascotState,
    inputState: MiffanMascotInputState,
    motion: MiffanMotionTuning,
): State<MiffanFacePose> = animateValueAsState(
    targetValue = miffanFacePose(state, inputState),
    typeConverter = MiffanFacePose.VectorConverter,
    animationSpec = motion.springSpec(360f),
    label = "miffan_face",
)

internal data class MiffanGazeTarget(val x: Float, val y: Float, val holdMillis: Long)

/** Choose a destination, then let the eyes rest there; never orbit continuously. */
internal fun nextMiffanGaze(
    state: MiffanMascotState,
    dayPhase: MiffanDayPhase,
    step: Int,
    random: Random = Random.Default,
): MiffanGazeTarget {
    fun jitter() = (random.nextFloat() - 0.5f) * 0.8f
    return when (state) {
        MiffanMascotState.Thinking -> {
            val direction = when (step % 4) {
                0 -> -1f
                2 -> 1f
                else -> 0f
            }
            val resting = step % 4 == 3
            MiffanGazeTarget(
                x = direction * 3.8f + if (resting) 0f else jitter(),
                y = if (resting) 0f else -2.6f + jitter(),
                holdMillis = if (resting) random.nextLong(700, 1_200) else random.nextLong(1_400, 2_500),
            )
        }
        MiffanMascotState.Error -> MiffanGazeTarget(-1.6f, 1.6f, 3_000)
        MiffanMascotState.UpdateAvailable -> MiffanGazeTarget(2.6f, -1.8f, 3_000)
        MiffanMascotState.Happy -> MiffanGazeTarget(jitter(), -0.6f, random.nextLong(2_000, 3_500))
        MiffanMascotState.Idle -> {
            val hold = when (dayPhase) {
                MiffanDayPhase.Morning -> random.nextLong(2_200, 4_200)
                MiffanDayPhase.Noon -> random.nextLong(1_800, 3_800)
                MiffanDayPhase.Night -> random.nextLong(3_200, 5_400)
            }
            if (step % 3 == 0) {
                MiffanGazeTarget(0f, 0f, hold)
            } else {
                val range = if (dayPhase == MiffanDayPhase.Night) 1.8f else 3.2f
                MiffanGazeTarget(
                    x = (random.nextFloat() * 2f - 1f) * range,
                    y = (random.nextFloat() * 2f - 1f) * 1.2f,
                    holdMillis = hold,
                )
            }
        }
    }
}

internal class MiffanAttentionAnimation(
    val lookAt: Offset?,
    val expression: State<Float>,
    val squash: State<Float>,
    val offsetY: State<Float>,
    val tilt: State<Float>,
)

/**
 * Timers change targets, not animation values. A new poke can cancel the timer
 * without resetting the springs, so their position and velocity survive it.
 */
@Composable
internal fun rememberMiffanAttention(
    eventId: Int,
    target: Offset,
    motion: MiffanMotionTuning,
): MiffanAttentionAnimation {
    var looking by remember { mutableStateOf(false) }
    var responding by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bodyDirection by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(eventId) {
        if (eventId == 0) return@LaunchedEffect
        looking = true
        delay(motion.attentionBodyDelayMillis)
        bodyDirection = if (abs(target.x) > 0.4f) -sign(target.x) else 0.35f
        responding = true
        pressed = true
        delay(motion.duration(110).toLong())
        pressed = false
        delay(motion.duration(440).toLong())
        looking = false
        responding = false
    }

    return MiffanAttentionAnimation(
        lookAt = target.takeIf { looking },
        expression = animateFloatAsState(
            targetValue = if (responding) 1f else 0f,
            animationSpec = motion.springSpec(420f),
            label = "miffan_attention_expression",
        ),
        squash = animateFloatAsState(
            targetValue = if (pressed) motion.tapSquash else 1f,
            animationSpec = motion.springSpec(850f, if (motion.tapOvershoot > 1f) 0.8f else 1f),
            label = "miffan_attention_squash",
        ),
        offsetY = animateFloatAsState(
            targetValue = if (pressed) motion.tapOffset else 0f,
            animationSpec = motion.springSpec(620f, 0.85f),
            label = "miffan_attention_offset",
        ),
        tilt = animateFloatAsState(
            targetValue = if (responding) bodyDirection * motion.attentionTiltDegrees else 0f,
            animationSpec = motion.springSpec(240f),
            label = "miffan_attention_tilt",
        ),
    )
}
