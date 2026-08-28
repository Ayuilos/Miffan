package me.ayuilos.miffan.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanColorSource
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.ui.context.LocalSettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

enum class MiffanMascotState {
    Idle,
    Thinking,
    Happy,
    Error,
    UpdateAvailable,
}

enum class MiffanMascotInputState {
    Inactive,
    Focused,
    Typing,
}

@Immutable
data class MiffanColors(
    val bowl: Color,
    val rim: Color,
    val rice: Color,
    val face: Color,
    val cueSurface: Color,
    val cueInk: Color,
)

enum class MiffanContentStyle {
    Rice,
    SproutedRice,
    Dumplings,
    StarRice,
}

enum class MiffanBowlFinish {
    Smooth,
    Fluted,
    Banded,
    Speckled,
}

enum class MiffanAccessoryStyle {
    None,
    Sprout,
    Spoon,
    StarCharm,
}

@Immutable
data class MiffanKindStyle(
    val content: MiffanContentStyle,
    val bowlFinish: MiffanBowlFinish,
    val accessory: MiffanAccessoryStyle,
)

fun MiffanKind.miffanKindStyle(): MiffanKindStyle = when (this) {
    MiffanKind.RICE -> MiffanKindStyle(
        content = MiffanContentStyle.Rice,
        bowlFinish = MiffanBowlFinish.Smooth,
        accessory = MiffanAccessoryStyle.None,
    )
    MiffanKind.SPROUT -> MiffanKindStyle(
        content = MiffanContentStyle.SproutedRice,
        bowlFinish = MiffanBowlFinish.Fluted,
        accessory = MiffanAccessoryStyle.Sprout,
    )
    MiffanKind.DUMPLING -> MiffanKindStyle(
        content = MiffanContentStyle.Dumplings,
        bowlFinish = MiffanBowlFinish.Banded,
        accessory = MiffanAccessoryStyle.Spoon,
    )
    MiffanKind.STARGAZER -> MiffanKindStyle(
        content = MiffanContentStyle.StarRice,
        bowlFinish = MiffanBowlFinish.Speckled,
        accessory = MiffanAccessoryStyle.StarCharm,
    )
}

fun MiffanPalette.miffanColors(): MiffanColors = when (this) {
    MiffanPalette.CLASSIC -> MiffanColors(
        bowl = Color(0xFFC76644),
        rim = Color(0xFFD6724F),
        rice = Color(0xFFFFE8A9),
        face = Color(0xFFFFE8A9),
        cueSurface = Color(0xFFFFF3D2),
        cueInk = Color(0xFFC76644),
    )
    MiffanPalette.MATCHA -> MiffanColors(
        bowl = Color(0xFF5F7F50),
        rim = Color(0xFF769761),
        rice = Color(0xFFE8F0B4),
        face = Color(0xFFF4F5D5),
        cueSurface = Color(0xFFEFF5D8),
        cueInk = Color(0xFF5F7F50),
    )
    MiffanPalette.SAKURA -> MiffanColors(
        bowl = Color(0xFFC86F83),
        rim = Color(0xFFDC8497),
        rice = Color(0xFFFFE5D7),
        face = Color(0xFFFFE9D9),
        cueSurface = Color(0xFFFFF0F3),
        cueInk = Color(0xFFC86F83),
    )
    MiffanPalette.MOONLIGHT -> MiffanColors(
        bowl = Color(0xFF5A5F91),
        rim = Color(0xFF7278AA),
        rice = Color(0xFFE2DCF8),
        face = Color(0xFFF0E9FF),
        cueSurface = Color(0xFFEEEBFF),
        cueInk = Color(0xFF5A5F91),
    )
    MiffanPalette.SEA_SALT -> MiffanColors(
        bowl = Color(0xFF3E8391),
        rim = Color(0xFF59A0AD),
        rice = Color(0xFFDCF1E8),
        face = Color(0xFFE8FAF4),
        cueSurface = Color(0xFFE7F7F6),
        cueInk = Color(0xFF3E8391),
    )
    MiffanPalette.INK_JADE -> MiffanColors(
        bowl = Color(0xFF354947),
        rim = Color(0xFF49635E),
        rice = Color(0xFF9EDBC3),
        face = Color(0xFFB9E9D5),
        cueSurface = Color(0xFFE2F2EC),
        cueInk = Color(0xFF354947),
    )
}

fun ColorScheme.miffanColors(): MiffanColors = MiffanColors(
    bowl = primary,
    rim = secondary,
    rice = primaryContainer,
    face = onPrimary,
    cueSurface = secondaryContainer,
    cueInk = onSecondaryContainer,
)

@Immutable
data class MiffanMotionTuning(
    val durationScale: Float,
    val breathAmplitude: Float,
    val gazeAmplitude: Float,
    val gazeIntervalScale: Float,
    val gestureIntervalScale: Float,
    val doubleBlinkChance: Float,
    val attentionBodyDelayMillis: Long,
    val attentionTiltDegrees: Float,
    val tapSquash: Float,
    val tapOvershoot: Float,
    val tapOffset: Float,
    val thinkingBob: Float,
    val happyBob: Float,
    val inputLookY: Float,
    val inputLift: Float,
    val submitNod: Float,
    val stateAmplitude: Float,
) {
    fun duration(baseMillis: Int): Int =
        (baseMillis * durationScale).roundToInt().coerceAtLeast(1)
}

fun MiffanMotionProfile.miffanMotionTuning(): MiffanMotionTuning = when (this) {
    MiffanMotionProfile.LIVELY -> MiffanMotionTuning(
        durationScale = 0.78f,
        breathAmplitude = 1.18f,
        gazeAmplitude = 1.08f,
        gazeIntervalScale = 0.72f,
        gestureIntervalScale = 0.76f,
        doubleBlinkChance = 0.32f,
        attentionBodyDelayMillis = 0L,
        attentionTiltDegrees = 1.3f,
        tapSquash = 0.982f,
        tapOvershoot = 1.006f,
        tapOffset = 0.9f,
        thinkingBob = 2.6f,
        happyBob = 3.6f,
        inputLookY = 3.6f,
        inputLift = 0.9f,
        submitNod = 1.8f,
        stateAmplitude = 1.1f,
    )
    MiffanMotionProfile.CALM -> MiffanMotionTuning(
        durationScale = 1.28f,
        breathAmplitude = 0.65f,
        gazeAmplitude = 0.55f,
        gazeIntervalScale = 1.35f,
        gestureIntervalScale = 1.4f,
        doubleBlinkChance = 0.12f,
        attentionBodyDelayMillis = 110L,
        attentionTiltDegrees = 0.7f,
        tapSquash = 0.992f,
        tapOvershoot = 1.002f,
        tapOffset = 0.25f,
        thinkingBob = 1.2f,
        happyBob = 1.7f,
        inputLookY = 2.8f,
        inputLift = 0.3f,
        submitNod = 0.7f,
        stateAmplitude = 0.65f,
    )
    MiffanMotionProfile.CURIOUS -> MiffanMotionTuning(
        durationScale = 1f,
        breathAmplitude = 1f,
        gazeAmplitude = 1.15f,
        gazeIntervalScale = 0.9f,
        gestureIntervalScale = 1f,
        doubleBlinkChance = 0.22f,
        attentionBodyDelayMillis = 90L,
        attentionTiltDegrees = 2.1f,
        tapSquash = 0.988f,
        tapOvershoot = 1.004f,
        tapOffset = 0.55f,
        thinkingBob = 2.2f,
        happyBob = 3f,
        inputLookY = 4.2f,
        inputLift = 0.7f,
        submitNod = 1.4f,
        stateAmplitude = 1f,
    )
}

private fun MiffanMotionTuning.reduced(): MiffanMotionTuning = copy(
    durationScale = minOf(durationScale, 0.65f),
    breathAmplitude = 0.15f,
    gazeAmplitude = 0.25f,
    gazeIntervalScale = 1.5f,
    gestureIntervalScale = 3f,
    doubleBlinkChance = 0f,
    attentionBodyDelayMillis = 0L,
    attentionTiltDegrees = 0f,
    tapSquash = 0.997f,
    tapOvershoot = 1f,
    tapOffset = 0.1f,
    thinkingBob = 0.25f,
    happyBob = 0.4f,
    inputLookY = 2f,
    inputLift = 0f,
    submitNod = 0.15f,
    stateAmplitude = 0.25f,
)

private fun LongRange.scaleBy(factor: Float): LongRange =
    (first * factor).roundToLong().coerceAtLeast(1L)..
        (last * factor).roundToLong().coerceAtLeast(1L)

private enum class MiffanIdleGesture {
    None,
    Yawn,
    RiceBounce,
    Doze,
}

/**
 * The in-app form of the launcher icon. Its pieces are drawn separately so the
 * bowl can breathe while the face and rice react to the current chat state.
 */
@Composable
fun MiffanMascot(
    state: MiffanMascotState,
    modifier: Modifier = Modifier,
    appearance: MiffanAppearance = MiffanAppearance(),
    motionProfile: MiffanMotionProfile = MiffanMotionProfile.CURIOUS,
    reducedMotion: Boolean = false,
    presentation: MiffanPresentation = MiffanPresentation.Scene,
    interactive: Boolean = false,
    attentionTarget: Offset? = null,
    attentionId: Int = 0,
    inputState: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    submitId: Int = 0,
    dayPhase: MiffanDayPhase = MiffanDayPhase.Noon,
    previewIdleGestures: Boolean = false,
    previewSignatureBehavior: Boolean = false,
) {
    val appColorScheme = MaterialTheme.colorScheme
    val colors = remember(appearance.palette, appearance.colorSource, appColorScheme) {
        when (appearance.colorSource) {
            MiffanColorSource.PALETTE -> appearance.palette.miffanColors()
            MiffanColorSource.APP_THEME -> appColorScheme.miffanColors()
        }
    }
    val motionReduced = reducedMotion || rememberMiffanReducedMotion()
    val runAmbient = miffanRunsAmbientMotion(presentation, state, motionReduced)
    val motion = remember(motionProfile, motionReduced, presentation) {
        motionProfile.miffanMotionTuning().let {
            if (presentation == MiffanPresentation.Avatar) it.forAvatar() else it
        }.let { tuning ->
            if (motionReduced) tuning.reduced() else tuning
        }
    }
    val kindBehavior = remember(appearance.kind) {
        appearance.kind.miffanKindBehavior()
    }
    val breath by rememberMiffanCycle(
        runAmbient,
        motion.duration(when (dayPhase) {
            MiffanDayPhase.Morning -> 2_600
            MiffanDayPhase.Noon -> 2_100
            MiffanDayPhase.Night -> 3_000
        }),
        reverse = true, label = "miffan_breath",
    )
    val thinkingPhase by rememberMiffanCycle(
        runAmbient, motion.duration(1500), peak = 360f, label = "miffan_thinking",
    )
    val inputPulse by rememberMiffanCycle(
        runAmbient, motion.duration(760), reverse = true, label = "miffan_input_pulse",
    )
    val signaturePhase by rememberMiffanCycle(
        runAmbient, motion.duration(kindBehavior.cycleMillis), peak = 360f, label = "miffan_kind_signature",
    )

    val blink = remember { Animatable(1f) }
    var ambientGaze by remember { mutableStateOf(Offset.Zero) }
    val idleGestureProgress = remember { Animatable(0f) }
    val submitProgress = remember { Animatable(0f) }
    var idleGesture by remember { mutableStateOf(MiffanIdleGesture.None) }
    var submitActive by remember { mutableStateOf(false) }
    var tapTargetX by remember { mutableFloatStateOf(0f) }
    var tapTargetY by remember { mutableFloatStateOf(0f) }
    var pokeCount by remember { mutableIntStateOf(0) }
    val attention = rememberMiffanAttention(
        pokeCount,
        Offset(tapTargetX, tapTargetY) * motion.gazeAmplitude.coerceAtMost(1f),
        motion,
    )
    val inputEngaged = state == MiffanMascotState.Idle && inputState != MiffanMascotInputState.Inactive
    val inputFocusProgress by animateFloatAsState(
        targetValue = if (inputEngaged) 1f else 0f,
        animationSpec = tween(
            durationMillis = motion.duration(if (inputEngaged) 240 else 280),
            easing = FastOutSlowInEasing,
        ),
        label = "miffan_input_focus",
    )
    val inputTypingProgress by animateFloatAsState(
        targetValue = if (inputEngaged && inputState == MiffanMascotInputState.Typing) 1f else 0f,
        animationSpec = tween(durationMillis = motion.duration(220), easing = FastOutSlowInEasing),
        label = "miffan_input_typing",
    )
    val gaze by animateOffsetAsState(
        targetValue = (attention.lookAt.takeUnless { state == MiffanMascotState.Error }) ?: when {
            previewSignatureBehavior -> Offset.Zero
            inputEngaged -> Offset(0f, motion.inputLookY)
            else -> ambientGaze
        },
        animationSpec = motion.springSpec(520f),
        label = "miffan_gaze",
    )
    val face by animateMiffanFace(state, inputState, motion)
    val semanticTransition = updateTransition(state, label = "miffan_semantic_state")
    val thinkingBlend = semanticTransition.animateFloat(
        transitionSpec = { motion.springSpec(260f) }, label = "thinking",
    ) { if (it == MiffanMascotState.Thinking) 1f else 0f }
    val happyBlend = semanticTransition.animateFloat(
        transitionSpec = { motion.springSpec(260f) }, label = "happy",
    ) { if (it == MiffanMascotState.Happy) 1f else 0f }
    val errorBlend = semanticTransition.animateFloat(
        transitionSpec = { motion.springSpec(260f) }, label = "error",
    ) { if (it == MiffanMascotState.Error) 1f else 0f }
    val updateBlend = semanticTransition.animateFloat(
        transitionSpec = { motion.springSpec(260f) }, label = "update",
    ) { if (it == MiffanMascotState.UpdateAvailable) 1f else 0f }
    val baseSignatureStrength by animateFloatAsState(
        targetValue = if (previewSignatureBehavior) {
            if (motionReduced) motion.stateAmplitude else 1f
        } else {
            if (!runAmbient) 0f else
                kindBehavior.strengthFor(state, inputState, dayPhase, submitProgress = 0f) * motion.stateAmplitude
        }.coerceIn(0f, 1f),
        animationSpec = motion.springSpec(260f),
        label = "miffan_signature_strength",
    )

    LaunchedEffect(submitId) {
        if (submitId == 0 || state == MiffanMascotState.Error) return@LaunchedEffect
        submitActive = true
        submitProgress.snapTo(0f)
        submitProgress.animateTo(1f, tween(motion.duration(420), easing = FastOutSlowInEasing))
        submitActive = false
        submitProgress.snapTo(0f)
    }

    LaunchedEffect(state, dayPhase, motion, runAmbient) {
        blink.animateTo(1f, tween(motion.duration(100)))
        if (!runAmbient) return@LaunchedEffect
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
            val scaledDelayRange = delayRange.scaleBy(motion.gazeIntervalScale)
            val closeDuration = motion.duration(
                if (state == MiffanMascotState.Idle && dayPhase == MiffanDayPhase.Night) 110 else 70
            )
            delay(Random.nextLong(scaledDelayRange.first, scaledDelayRange.last + 1))
            blink.animateTo(0.08f, tween(closeDuration))
            blink.animateTo(1f, tween(closeDuration + motion.duration(50)))
            if (Random.nextFloat() < motion.doubleBlinkChance) {
                delay(motion.duration(110).toLong())
                blink.animateTo(0.08f, tween(motion.duration(60)))
                blink.animateTo(1f, tween(motion.duration(100)))
            }
        }
    }

    LaunchedEffect(state, dayPhase, motion, previewSignatureBehavior, inputEngaged, runAmbient) {
        if (!runAmbient || previewSignatureBehavior || inputEngaged) {
            ambientGaze = Offset.Zero
            return@LaunchedEffect
        }
        var gazeStep = 0
        while (currentCoroutineContext().isActive) {
            val target = nextMiffanGaze(state, dayPhase, gazeStep++)
            ambientGaze = Offset(target.x, target.y) * motion.gazeAmplitude
            delay((target.holdMillis * motion.gazeIntervalScale).roundToLong())
        }
    }

    LaunchedEffect(state, dayPhase, previewIdleGestures, previewSignatureBehavior, motion, runAmbient, presentation) {
        idleGestureProgress.animateTo(0f, tween(motion.duration(200)))
        idleGesture = MiffanIdleGesture.None
        if (!runAmbient || presentation == MiffanPresentation.Avatar ||
            state != MiffanMascotState.Idle || previewSignatureBehavior) return@LaunchedEffect

        delay(
            if (previewIdleGestures) {
                motion.duration(700).toLong()
            } else {
                val initialRange = (6_000L..12_000L).scaleBy(motion.gestureIntervalScale)
                Random.nextLong(initialRange.first, initialRange.last + 1)
            }
        )
        while (currentCoroutineContext().isActive) {
            idleGesture = when (dayPhase) {
                MiffanDayPhase.Morning -> MiffanIdleGesture.Yawn
                MiffanDayPhase.Noon -> MiffanIdleGesture.RiceBounce
                MiffanDayPhase.Night -> MiffanIdleGesture.Doze
            }
            when (idleGesture) {
                MiffanIdleGesture.Yawn -> {
                    idleGestureProgress.animateTo(1f, tween(motion.duration(650), easing = FastOutSlowInEasing))
                    delay(motion.duration(420).toLong())
                    idleGestureProgress.animateTo(0f, tween(motion.duration(720), easing = FastOutSlowInEasing))
                }

                MiffanIdleGesture.RiceBounce -> {
                    idleGestureProgress.animateTo(1f, tween(motion.duration(760), easing = FastOutSlowInEasing))
                    idleGestureProgress.snapTo(0f)
                }

                MiffanIdleGesture.Doze -> {
                    idleGestureProgress.animateTo(1f, tween(motion.duration(900), easing = FastOutSlowInEasing))
                    delay(motion.duration(1_250).toLong())
                    idleGestureProgress.animateTo(0f, tween(motion.duration(780), easing = FastOutSlowInEasing))
                }

                MiffanIdleGesture.None -> Unit
            }
            idleGesture = MiffanIdleGesture.None
            delay(
                if (previewIdleGestures) {
                    motion.duration(1_400).toLong()
                } else {
                    val repeatRange = when (dayPhase) {
                        MiffanDayPhase.Morning -> Random.nextLong(22_000L, 38_000L)
                        MiffanDayPhase.Noon -> Random.nextLong(16_000L, 30_000L)
                        MiffanDayPhase.Night -> Random.nextLong(26_000L, 46_000L)
                    }
                    (repeatRange * motion.gestureIntervalScale).roundToLong()
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

    val stateRotation by animateFloatAsState(
        targetValue = if (state == MiffanMascotState.Error) -6f * motion.stateAmplitude else 0f,
        animationSpec = motion.springSpec(180f),
        label = "miffan_state_rotation",
    )
    val stateOffsetY by animateFloatAsState(
        targetValue = when (state) {
            MiffanMascotState.Happy -> -5f * motion.stateAmplitude
            MiffanMascotState.Error -> 6f * motion.stateAmplitude
            MiffanMascotState.UpdateAvailable -> -2f * motion.stateAmplitude
            else -> 0f
        },
        animationSpec = motion.springSpec(220f, if (motionReduced) 1f else 0.9f),
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
        val thinkingProgress = thinkingBlend.value.coerceIn(0f, 1f)
        val happyProgress = happyBlend.value.coerceIn(0f, 1f)
        val errorProgress = errorBlend.value.coerceIn(0f, 1f)
        val updateProgress = updateBlend.value.coerceIn(0f, 1f)
        val rawGestureProgress = idleGestureProgress.value
        val submitAmount = if (submitActive) submitProgress.value else 0f
        val signatureStrength = maxOf(
            baseSignatureStrength,
            kindBehavior.submitStrength * sin(submitAmount * Math.PI).toFloat() * motion.stateAmplitude,
        ).coerceIn(0f, 1f)
        val signaturePose = kindBehavior.poseFor(
            phaseDegrees = signaturePhase,
            strength = signatureStrength,
            state = state,
            inputState = inputState,
            settleProgress = errorProgress,
            listeningProgress = inputFocusProgress,
        )
        val reaction = attention.expression.value.coerceIn(0f, 1f) * (1f - errorProgress)
        val gestureVisibility =
            (1f - reaction) * (1f - inputFocusProgress) * (1f - submitAmount)
        val yawnAmount = if (idleGesture == MiffanIdleGesture.Yawn) {
            rawGestureProgress * gestureVisibility * motion.breathAmplitude
        } else {
            0f
        }
        val dozeAmount = if (idleGesture == MiffanIdleGesture.Doze) {
            rawGestureProgress * gestureVisibility * motion.breathAmplitude
        } else {
            0f
        }
        val riceBounceAmount = if (idleGesture == MiffanIdleGesture.RiceBounce) {
            sin(rawGestureProgress * Math.PI).toFloat().coerceAtLeast(0f) *
                gestureVisibility * motion.breathAmplitude
        } else {
            0f
        }
        val breathAmplitude = when (dayPhase) {
            MiffanDayPhase.Morning -> 1.3f
            MiffanDayPhase.Noon -> 1.6f
            MiffanDayPhase.Night -> 1f
        }
        val idleBob = -breath * breathAmplitude * motion.breathAmplitude -
            riceBounceAmount * 0.8f + dozeAmount * 1.5f
        val bodyBob = if (previewSignatureBehavior) 0f else
            idleBob * (1f - thinkingProgress - happyProgress - updateProgress).coerceIn(0f, 1f) +
                sin(Math.toRadians(thinkingPhase.toDouble())).toFloat() * motion.thinkingBob * thinkingProgress -
                breath * motion.happyBob * (happyProgress + updateProgress * 0.45f)
        val submitNod = if (submitActive) {
            sin(submitAmount * Math.PI).toFloat().coerceAtLeast(0f) * motion.submitNod
        } else {
            0f
        }
        val bodyScaleY =
            (1f + breath * 0.018f * motion.breathAmplitude + yawnAmount * 0.025f +
                inputFocusProgress * 0.006f) * attention.squash.value
        val bodyScaleX =
            (1f - breath * 0.008f * motion.breathAmplitude - yawnAmount * 0.01f) *
                (2f - attention.squash.value)

        withTransform({
            translate(left, top)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            val airborne = (-signaturePose.offsetY / 9f).coerceIn(0f, 1f)
            val shadowWidth = 102f * (1f - airborne * 0.28f)
            drawOval(
                color = colors.bowl.copy(alpha = 0.16f * (1f - airborne * 0.58f)),
                topLeft = Offset(100f - shadowWidth / 2f, 169f + stateOffsetY),
                size = Size(
                    shadowWidth,
                    (11f - bodyBob.coerceAtMost(2f)) * (1f - airborne * 0.22f),
                ),
            )

            withTransform({
                translate(
                    signaturePose.offsetX,
                    bodyBob + stateOffsetY + attention.offsetY.value +
                        inputFocusProgress * motion.inputLift + submitNod +
                        signaturePose.offsetY,
                )
                rotate(
                    stateRotation + dozeAmount * 4f + attention.tilt.value +
                        signaturePose.rotationDegrees,
                    pivot = Offset(100f, 112f),
                )
                scale(
                    bodyScaleX * signaturePose.scaleX,
                    bodyScaleY * signaturePose.scaleY,
                    pivot = Offset(100f, 112f),
                )
            }) {
                drawMascotBody(
                    colors = colors,
                    kind = appearance.kind,
                    signature = kindBehavior.signature,
                    state = state,
                    face = face.blendTo(MiffanFacePose.Attention, reaction),
                    thinkingProgress = thinkingProgress,
                    errorProgress = errorProgress,
                    eyeScaleY =
                        blink.value *
                            (1f - yawnAmount * 0.78f - dozeAmount * 0.92f) *
                            (1f + inputFocusProgress * 0.07f),
                    lookX = gaze.x,
                    lookY = gaze.y,
                    thinkingPhase = thinkingPhase,
                    signaturePhase = signaturePhase,
                    signatureStrength = signatureStrength,
                    inputState = inputState,
                    idleGesture = idleGesture,
                    idleGestureProgress = rawGestureProgress,
                    idleGestureVisibility =
                        (gestureVisibility * motion.breathAmplitude).coerceIn(0f, 1.3f),
                )
            }

            if (state == MiffanMascotState.UpdateAvailable) {
                drawUpdateAvailableCue(
                    colors = colors,
                    pulse = inputPulse,
                    amplitude = motion.stateAmplitude,
                )
            }

            if (state == MiffanMascotState.Idle) {
                drawInputCue(
                    colors = colors,
                    focusProgress = inputFocusProgress,
                    typingProgress = inputTypingProgress,
                    pulse = inputPulse,
                    submitProgress = submitAmount,
                    submitting = submitActive,
                )
            }
        }
    }
}

private fun DrawScope.drawUpdateAvailableCue(
    colors: MiffanColors,
    pulse: Float,
    amplitude: Float,
) {
    val center = Offset(156f, 43f)
    val pulseScale = 0.92f + pulse * 0.08f * amplitude
    withTransform({ scale(pulseScale, pulseScale, pivot = center) }) {
        drawCircle(
            color = colors.cueSurface,
            radius = 17f,
            center = center,
        )
        drawCircle(
            color = colors.cueInk.copy(alpha = 0.28f),
            radius = 17f,
            center = center,
            style = Stroke(width = 3f),
        )
        drawLine(
            color = colors.cueInk,
            start = Offset(156f, 34f),
            end = Offset(156f, 51f),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.cueInk,
            start = Offset(149f, 45f),
            end = Offset(156f, 52f),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.cueInk,
            start = Offset(163f, 45f),
            end = Offset(156f, 52f),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawInputCue(
    colors: MiffanColors,
    focusProgress: Float,
    typingProgress: Float,
    pulse: Float,
    submitProgress: Float,
    submitting: Boolean,
) {
    val visibility = if (submitting) {
        1f - submitProgress * 0.15f
    } else {
        focusProgress
    }.coerceIn(0f, 1f)
    if (visibility <= 0.001f) return

    val bubbleCenter = Offset(152f, 24.5f)
    val catchPoint = Offset(113f, 63f)
    val travel = if (submitting) submitProgress.coerceIn(0f, 1f) else 0f
    val appearScale = 0.82f + focusProgress * 0.18f
    val bubbleScale = if (submitting) {
        1f - travel * 0.86f
    } else {
        appearScale
    }
    val bubbleColor = colors.cueSurface
    val inkColor = colors.cueInk
    val travelOffset = (catchPoint - bubbleCenter) * travel
    val settleOffset = Offset(0f, (1f - focusProgress) * 4f)

    withTransform({
        translate(
            left = travelOffset.x + settleOffset.x,
            top = travelOffset.y + settleOffset.y,
        )
        scale(bubbleScale, bubbleScale, pivot = bubbleCenter)
    }) {
        val tail = Path().apply {
            moveTo(145f, 39f)
            lineTo(154f, 49f)
            lineTo(160f, 39f)
            close()
        }
        drawPath(tail, bubbleColor.copy(alpha = visibility))
        drawRoundRect(
            color = bubbleColor.copy(alpha = visibility),
            topLeft = Offset(118f, 7f),
            size = Size(68f, 35f),
            cornerRadius = CornerRadius(11f, 11f),
        )
        drawRoundRect(
            color = inkColor.copy(alpha = visibility * 0.42f),
            topLeft = Offset(118f, 7f),
            size = Size(68f, 35f),
            cornerRadius = CornerRadius(11f, 11f),
            style = Stroke(width = 1.6f),
        )

        val contentVisibility = visibility * (1f - travel * 2f).coerceIn(0f, 1f)
        val cursorAlpha = (0.28f + (1f - pulse) * 0.72f) * (1f - typingProgress)
        drawRoundRect(
            color = inkColor.copy(alpha = contentVisibility * cursorAlpha),
            topLeft = Offset(150f, 14f),
            size = Size(3.6f, 21f),
            cornerRadius = CornerRadius(2f, 2f),
        )

        val lineAlpha = contentVisibility * typingProgress
        drawRoundRect(
            color = inkColor.copy(alpha = lineAlpha * 0.88f),
            topLeft = Offset(130f, 16f),
            size = Size(38f, 4f),
            cornerRadius = CornerRadius(2f, 2f),
        )
        drawRoundRect(
            color = inkColor.copy(alpha = lineAlpha * 0.62f),
            topLeft = Offset(130f, 26f),
            size = Size(25f + pulse * 4f, 4f),
            cornerRadius = CornerRadius(2f, 2f),
        )
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

private fun DrawScope.drawBackAccessory(
    accessory: MiffanAccessoryStyle,
    colors: MiffanColors,
    signaturePhase: Float,
    signatureStrength: Float,
) {
    if (accessory != MiffanAccessoryStyle.Spoon) return

    val spoonSway = sin(Math.toRadians(signaturePhase.toDouble())).toFloat() *
        4.2f * signatureStrength
    rotate(12f + spoonSway, pivot = Offset(164f, 69f)) {
        drawRoundRect(
            color = colors.cueInk.copy(alpha = 0.28f),
            topLeft = Offset(157f, 42f),
            size = Size(9f, 81f),
            cornerRadius = CornerRadius(5f, 5f),
        )
        drawRoundRect(
            color = colors.cueSurface,
            topLeft = Offset(159f, 43f),
            size = Size(5f, 79f),
            cornerRadius = CornerRadius(3f, 3f),
        )
        drawOval(
            color = colors.cueInk.copy(alpha = 0.3f),
            topLeft = Offset(149f, 22f),
            size = Size(25f, 31f),
        )
        drawOval(
            color = colors.cueSurface,
            topLeft = Offset(152f, 24f),
            size = Size(19f, 25f),
        )
    }
}

private fun DrawScope.drawBowlFinish(
    finish: MiffanBowlFinish,
    colors: MiffanColors,
) {
    when (finish) {
        MiffanBowlFinish.Smooth -> Unit
        MiffanBowlFinish.Fluted -> {
            listOf(55f, 77f, 100f, 123f, 145f).forEach { x ->
                val distanceFromCenter = abs(x - 100f) / 45f
                drawLine(
                    color = colors.rim.copy(alpha = 0.44f),
                    start = Offset(x, 99f + distanceFromCenter * 3f),
                    end = Offset(100f + (x - 100f) * 0.68f, 156f - distanceFromCenter * 5f),
                    strokeWidth = 2.4f,
                    cap = StrokeCap.Round,
                )
            }
        }
        MiffanBowlFinish.Banded -> {
            listOf(119f, 143f).forEach { y ->
                val band = Path().apply {
                    moveTo(34f + (y - 119f) * 0.32f, y)
                    cubicTo(68f, y + 8f, 132f, y + 8f, 166f - (y - 119f) * 0.32f, y)
                }
                drawPath(
                    path = band,
                    color = colors.rim.copy(alpha = 0.48f),
                    style = Stroke(width = 2.8f),
                )
            }
        }
        MiffanBowlFinish.Speckled -> {
            listOf(
                Offset(49f, 112f) to 2.4f,
                Offset(66f, 141f) to 1.8f,
                Offset(88f, 154f) to 2.2f,
                Offset(116f, 108f) to 1.9f,
                Offset(137f, 137f) to 2.6f,
                Offset(151f, 116f) to 1.6f,
            ).forEach { (center, radius) ->
                drawCircle(
                    color = colors.rim.copy(alpha = 0.62f),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}

private fun DrawScope.drawMascotContent(
    content: MiffanContentStyle,
    colors: MiffanColors,
    signaturePhase: Float,
    signatureStrength: Float,
    errorProgress: Float,
) {
    when (content) {
        MiffanContentStyle.Rice -> drawRiceMound(colors)
        MiffanContentStyle.SproutedRice -> {
            val radians = Math.toRadians(signaturePhase.toDouble())
            val sway = (sin(radians).toFloat() * 22f * (1f - errorProgress) +
                8f * errorProgress) * signatureStrength
            val leafPulse = 1f +
                (cos(radians).toFloat() * 0.045f + 0.045f) * signatureStrength
            withTransform({
                rotate(sway, pivot = Offset(101f, 61f))
                scale(1f, leafPulse, pivot = Offset(101f, 61f))
            }) {
                drawLine(
                    color = colors.cueInk,
                    start = Offset(101f, 61f),
                    end = Offset(101f, 27f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
                rotate(-30f, pivot = Offset(91f, 35f)) {
                    drawOval(
                        color = colors.cueInk,
                        topLeft = Offset(80f, 28f),
                        size = Size(23f, 13f),
                    )
                }
                rotate(31f, pivot = Offset(111f, 34f)) {
                    drawOval(
                        color = colors.rice,
                        topLeft = Offset(100f, 27f),
                        size = Size(23f, 13f),
                    )
                }
            }
            drawRiceMound(colors)
        }
        MiffanContentStyle.Dumplings -> {
            val radians = Math.toRadians(signaturePhase.toDouble())
            listOf(
                Triple(72f, 65f, 17f),
                Triple(128f, 65f, 17f),
                Triple(100f, 57f, 20f),
            ).forEachIndexed { index, (x, y, radius) ->
                val wave = sin(radians + index * Math.PI * 2.0 / 3.0).toFloat()
                val ripple = (-wave.coerceAtLeast(0f) * 9f * (1f - errorProgress) +
                    1.2f * errorProgress) * signatureStrength
                withTransform({ translate(0f, ripple) }) {
                    drawCircle(
                        color = if (index == 2) colors.rice else colors.rice.copy(alpha = 0.94f),
                        radius = radius,
                        center = Offset(x, y),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.28f),
                        startAngle = 205f,
                        sweepAngle = 78f,
                        useCenter = false,
                        topLeft = Offset(x - radius * 0.58f, y - radius * 0.58f),
                        size = Size(radius * 1.16f, radius * 1.16f),
                        style = Stroke(width = 2.2f),
                    )
                }
            }
        }
        MiffanContentStyle.StarRice -> {
            drawRiceMound(colors, top = 61f, highlight = false)
            val radians = Math.toRadians(signaturePhase.toDouble())
            val starWave = (sin(radians).toFloat() + 1f) / 2f
            val errorTilt = -5f * signatureStrength * errorProgress
            withTransform({
                scale(
                    scaleX = 1f + starWave * 0.08f * signatureStrength,
                    scaleY = 1f + starWave * 0.08f * signatureStrength,
                    pivot = Offset(100f, 53f),
                )
            }) {
                drawStar(
                    center = Offset(100f, 53f),
                    outerRadius = 22f,
                    innerRadius = 10f,
                    color = colors.rice,
                    rotationDegrees = -90f +
                        sin(radians).toFloat() * 10f * signatureStrength + errorTilt,
                )
            }
            drawStar(
                center = Offset(68f, 65f),
                outerRadius = 13f,
                innerRadius = 6f,
                color = colors.rice.copy(alpha = 0.94f),
                rotationDegrees = -72f - sin(radians).toFloat() * 5f * signatureStrength,
            )
            drawStar(
                center = Offset(134f, 66f),
                outerRadius = 12f,
                innerRadius = 5.5f,
                color = colors.rice.copy(alpha = 0.94f),
                rotationDegrees = -108f + sin(radians).toFloat() * 5f * signatureStrength,
            )
        }
    }
}

private fun DrawScope.drawRiceMound(
    colors: MiffanColors,
    top: Float = 43f,
    highlight: Boolean = true,
) {
    val offset = top - 43f
    val rice = Path().apply {
        moveTo(38f, 69f)
        cubicTo(42f, 62f, 53f, 58f, 66f, 58f)
        cubicTo(73f, 50f + offset, 86f, 48f + offset, 98f, 51f + offset)
        cubicTo(110f, 43f + offset, 128f, 44f + offset, 137f, 52f + offset)
        cubicTo(150f, 52f, 160f, 58f, 162f, 66f)
        cubicTo(156f, 76f, 131f, 82f, 101f, 83f)
        cubicTo(72f, 84f, 47f, 79f, 38f, 72f)
        close()
    }
    drawPath(rice, colors.rice)
    if (highlight) {
        val riceHighlight = Path().apply {
            moveTo(54f, 66f)
            cubicTo(68f, 56f + offset * 0.5f, 91f, 53f + offset * 0.5f, 113f, 55f + offset * 0.5f)
            cubicTo(101f, 61f, 80f, 67f, 54f, 69f)
            close()
        }
        drawPath(riceHighlight, Color.White.copy(alpha = 0.24f))
    }
}

private fun DrawScope.drawFrontAccessory(
    accessory: MiffanAccessoryStyle,
    colors: MiffanColors,
    signaturePhase: Float,
    signatureStrength: Float,
) {
    if (accessory != MiffanAccessoryStyle.StarCharm) return

    val radians = Math.toRadians(signaturePhase.toDouble())
    val charmSway = sin(radians).toFloat() * 6f * signatureStrength
    rotate(charmSway, pivot = Offset(155f, 74f)) {
        drawLine(
            color = colors.face.copy(alpha = 0.76f),
            start = Offset(155f, 74f),
            end = Offset(169f, 94f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round,
        )
        drawStar(
            center = Offset(172f, 99f),
            outerRadius = 9f,
            innerRadius = 4.2f,
            color = colors.face,
            rotationDegrees = -18f + charmSway,
        )
    }
}

private fun DrawScope.drawKindSignatureCue(
    signature: MiffanSignatureMotion,
    colors: MiffanColors,
    state: MiffanMascotState,
    inputState: MiffanMascotInputState,
    phase: Float,
    strength: Float,
) {
    if (strength <= 0.001f || state == MiffanMascotState.Error) return

    val radians = Math.toRadians(phase.toDouble())
    val wave = (sin(radians).toFloat() + 1f) / 2f
    when (signature) {
        MiffanSignatureMotion.GrainHop -> {
            if (state == MiffanMascotState.Thinking) return
            val hop = wave * strength
            drawContentParticle(
                content = MiffanContentStyle.Rice,
                colors = colors,
                center = Offset(119f + sin(radians * 0.5).toFloat() * 3f, 55f - hop * 14f),
                rotation = -12f + wave * 24f,
                alpha = (hop * 1.2f).coerceIn(0f, 0.95f),
            )
        }
        MiffanSignatureMotion.LeafSway -> {
            if (
                state != MiffanMascotState.Thinking &&
                state != MiffanMascotState.Happy &&
                inputState == MiffanMascotInputState.Inactive
            ) {
                return
            }
            val cueAlpha = (strength * (0.3f + wave * 0.55f)).coerceIn(0f, 0.72f)
            drawLine(
                color = colors.cueInk.copy(alpha = cueAlpha),
                start = Offset(126f, 25f),
                end = Offset(134f + wave * 2f, 20f),
                strokeWidth = 3.2f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.cueInk.copy(alpha = cueAlpha * 0.72f),
                start = Offset(129f, 34f),
                end = Offset(138f + wave * 2f, 34f),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }
        MiffanSignatureMotion.DumplingRipple -> {
            if (state != MiffanMascotState.Thinking && inputState != MiffanMascotInputState.Typing) {
                return
            }
            listOf(
                76f to 0.0,
                100f to Math.PI * 2.0 / 3.0,
                124f to Math.PI * 4.0 / 3.0,
            ).forEach { (x, phaseOffset) ->
                val steamWave = (sin(radians + phaseOffset).toFloat() + 1f) / 2f
                val steam = Path().apply {
                    moveTo(x, 44f)
                    cubicTo(
                        x - 7f,
                        34f - steamWave * 5f * strength,
                        x + 7f,
                        24f - steamWave * 7f * strength,
                        x,
                        10f - steamWave * 8f * strength,
                    )
                }
                drawPath(
                    path = steam,
                    color = colors.cueInk.copy(
                        alpha = (strength * (0.52f + steamWave * 0.46f)).coerceAtMost(0.94f),
                    ),
                    style = Stroke(width = 5f, cap = StrokeCap.Round),
                )
            }
        }
        MiffanSignatureMotion.StarTwinkle -> {
            val visibility = strength * (0.48f + wave * 0.5f)
            drawCircle(
                color = colors.cueInk.copy(alpha = visibility * 0.16f),
                radius = 29f + wave * 5f,
                center = Offset(100f, 53f),
                style = Stroke(width = 3.2f),
            )
            drawSparkle(
                center = Offset(53f, 25f),
                radius = 7f + wave * 2.5f,
                color = colors.cueInk.copy(alpha = visibility.coerceIn(0f, 0.92f)),
            )
            drawSparkle(
                center = Offset(148f, 34f),
                radius = 5.5f + (1f - wave) * 2f,
                color = colors.cueInk.copy(alpha = (visibility * 0.82f).coerceIn(0f, 0.78f)),
            )
            drawSparkle(
                center = Offset(160f, 65f),
                radius = 3.8f + wave,
                color = colors.face.copy(alpha = (visibility * 0.7f).coerceIn(0f, 0.7f)),
            )
        }
    }
}

private fun DrawScope.drawSparkle(
    center: Offset,
    radius: Float,
    color: Color,
) {
    drawLine(
        color = color,
        start = Offset(center.x - radius, center.y),
        end = Offset(center.x + radius, center.y),
        strokeWidth = 3.8f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y - radius),
        end = Offset(center.x, center.y + radius),
        strokeWidth = 3.8f,
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = color,
        radius = 2.1f,
        center = center,
    )
}

private fun DrawScope.drawContentParticle(
    content: MiffanContentStyle,
    colors: MiffanColors,
    center: Offset,
    rotation: Float,
    alpha: Float = 1f,
) {
    when (content) {
        MiffanContentStyle.Dumplings -> {
            drawCircle(
                color = colors.rice.copy(alpha = alpha),
                radius = 4.8f,
                center = center,
            )
        }
        MiffanContentStyle.StarRice -> {
            drawStar(
                center = center,
                outerRadius = 6f,
                innerRadius = 2.8f,
                color = colors.rice.copy(alpha = alpha),
                rotationDegrees = rotation,
            )
        }
        MiffanContentStyle.Rice,
        MiffanContentStyle.SproutedRice,
        -> rotate(rotation, pivot = center) {
            drawRoundRect(
                color = colors.rice.copy(alpha = alpha),
                topLeft = Offset(center.x - 4.5f, center.y - 2.2f),
                size = Size(9f, 4.4f),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

private fun DrawScope.drawStar(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    color: Color,
    rotationDegrees: Float = -90f,
) {
    val path = Path()
    repeat(10) { index ->
        val radius = if (index % 2 == 0) outerRadius else innerRadius
        val angle = Math.toRadians((rotationDegrees + index * 36f).toDouble())
        val point = Offset(
            x = center.x + cos(angle).toFloat() * radius,
            y = center.y + sin(angle).toFloat() * radius,
        )
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawMascotBody(
    colors: MiffanColors,
    kind: MiffanKind,
    signature: MiffanSignatureMotion,
    state: MiffanMascotState,
    face: MiffanFacePose,
    thinkingProgress: Float,
    errorProgress: Float,
    eyeScaleY: Float,
    lookX: Float,
    lookY: Float,
    thinkingPhase: Float,
    signaturePhase: Float,
    signatureStrength: Float,
    inputState: MiffanMascotInputState,
    idleGesture: MiffanIdleGesture,
    idleGestureProgress: Float,
    idleGestureVisibility: Float,
) {
    val kindStyle = kind.miffanKindStyle()
    drawBackAccessory(
        accessory = kindStyle.accessory,
        colors = colors,
        signaturePhase = signaturePhase,
        signatureStrength = signatureStrength,
    )

    val bowl = Path().apply {
        moveTo(24f, 67f)
        cubicTo(24f, 52f, 58f, 43f, 100f, 43f)
        cubicTo(142f, 43f, 176f, 52f, 176f, 67f)
        lineTo(173f, 112f)
        cubicTo(168f, 149f, 140f, 170f, 100f, 170f)
        cubicTo(60f, 170f, 32f, 149f, 27f, 112f)
        close()
    }
    drawPath(bowl, colors.bowl)
    drawBowlFinish(kindStyle.bowlFinish, colors)

    val rim = Path().apply {
        moveTo(24f, 67f)
        cubicTo(24f, 52f, 58f, 43f, 100f, 43f)
        cubicTo(142f, 43f, 176f, 52f, 176f, 67f)
        cubicTo(176f, 82f, 142f, 93f, 100f, 93f)
        cubicTo(58f, 93f, 24f, 82f, 24f, 67f)
        close()
    }
    drawPath(rim, colors.rim)

    val riceBounce = if (idleGesture == MiffanIdleGesture.RiceBounce) {
        sin(idleGestureProgress * Math.PI).toFloat().coerceAtLeast(0f) * idleGestureVisibility
    } else {
        0f
    }
    withTransform({
        translate(0f, -riceBounce * 1.8f)
        scale(1f + riceBounce * 0.012f, 1f, pivot = Offset(100f, 69f))
    }) {
        drawMascotContent(
            content = kindStyle.content,
            colors = colors,
            signaturePhase = signaturePhase,
            signatureStrength = signatureStrength,
            errorProgress = errorProgress,
        )
    }

    drawFrontAccessory(
        accessory = kindStyle.accessory,
        colors = colors,
        signaturePhase = signaturePhase,
        signatureStrength = signatureStrength,
    )

    if (riceBounce > 0f) {
        val grainCenter = Offset(116f + idleGestureProgress * 5f, 51f - riceBounce * 13f)
        drawContentParticle(
            content = kindStyle.content,
            colors = colors,
            center = grainCenter,
            rotation = -18f + idleGestureProgress * 42f,
            alpha = riceBounce,
        )
    }

    if (thinkingProgress > 0.001f && signature == MiffanSignatureMotion.GrainHop) {
        val radians = Math.toRadians(thinkingPhase.toDouble())
        val grainX = 100f + cos(radians).toFloat() * 41f * signatureStrength
        val grainY = 52f - 12f * signatureStrength +
            sin(radians).toFloat() * 8f * signatureStrength
        drawContentParticle(
            content = kindStyle.content,
            colors = colors,
            center = Offset(grainX, grainY),
            rotation = thinkingPhase * signatureStrength + 18f,
            alpha = (0.38f + signatureStrength * 0.62f) * thinkingProgress,
        )
    }

    drawKindSignatureCue(
        signature = signature,
        colors = colors,
        state = state,
        inputState = inputState,
        phase = signaturePhase,
        strength = signatureStrength,
    )

    val eyeColor = colors.face
    listOf(70f, 130f).forEachIndexed { index, eyeCenterX ->
        val asymmetry = if (index == 0) face.eyeAsymmetry else -face.eyeAsymmetry
        val eyeHeight = 14f * (eyeScaleY * (face.eyeOpen + asymmetry)).coerceIn(0.08f, 1.4f)
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

    val yawn = if (idleGesture == MiffanIdleGesture.Yawn) {
        (idleGestureProgress * idleGestureVisibility).coerceIn(0f, 1f)
    } else {
        0f
    }
    val mouth = miffanMouthPath(face, yawn)
    drawPath(mouth, eyeColor)
    drawPath(mouth, eyeColor, style = Stroke(width = 1f))
}

internal fun miffanMouthPath(face: MiffanFacePose, yawnProgress: Float = 0f): Path {
    val yawn = yawnProgress.coerceIn(0f, 1f)
    val curve = face.mouthCurve.coerceIn(-1f, 1f) * (1f - yawn)
    val openness = face.mouthOpen.coerceIn(3f, 15f) + 11f * yawn
    val halfWidth = 5f + abs(curve) * 6f + (openness - 8f).coerceAtLeast(0f) * 0.3f
    val halfHeight = openness / 2f
    val mouthY = 128f - yawn
    val bend = curve * 8f

    // Four quarter-ellipse curves share tangents at every join. Two arcs meeting
    // at the sides make pointed, lemon-shaped lips even when the face is neutral.
    val ovalControl = 0.55228475f
    return Path().apply {
        moveTo(100f - halfWidth, mouthY)
        cubicTo(
            100f - halfWidth, mouthY - halfHeight * ovalControl,
            100f - halfWidth * ovalControl, mouthY + bend - halfHeight,
            100f, mouthY + bend - halfHeight,
        )
        cubicTo(
            100f + halfWidth * ovalControl, mouthY + bend - halfHeight,
            100f + halfWidth, mouthY - halfHeight * ovalControl,
            100f + halfWidth, mouthY,
        )
        cubicTo(
            100f + halfWidth, mouthY + halfHeight * ovalControl,
            100f + halfWidth * ovalControl, mouthY + bend + halfHeight,
            100f, mouthY + bend + halfHeight,
        )
        cubicTo(
            100f - halfWidth * ovalControl, mouthY + bend + halfHeight,
            100f - halfWidth, mouthY + halfHeight * ovalControl,
            100f - halfWidth, mouthY,
        )
        close()
    }
}
