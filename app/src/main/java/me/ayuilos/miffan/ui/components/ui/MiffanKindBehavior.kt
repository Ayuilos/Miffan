package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.Immutable
import me.ayuilos.miffan.data.model.MiffanKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class MiffanSignatureMotion {
    GrainHop,
    LeafSway,
    DumplingRipple,
    StarTwinkle,
}

/**
 * A small, renderer-owned behavior signature for each Miffan inhabitant.
 *
 * The chat scene continues to emit semantic state only. Character kind chooses
 * how that meaning is accented, while the motion profile still controls the
 * overall tempo and amplitude.
 */
@Immutable
data class MiffanKindBehavior(
    val signature: MiffanSignatureMotion,
    val cycleMillis: Int,
    val idleStrength: Float,
    val thinkingStrength: Float,
    val focusedStrength: Float,
    val typingStrength: Float,
    val happyStrength: Float,
    val errorStrength: Float,
    val submitStrength: Float,
    val nightIdleMultiplier: Float = 1f,
)

@Immutable
data class MiffanSignaturePose(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
)

fun MiffanKind.miffanKindBehavior(): MiffanKindBehavior = when (this) {
    MiffanKind.RICE -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.GrainHop,
        cycleMillis = 1_680,
        idleStrength = 0.3f,
        thinkingStrength = 1f,
        focusedStrength = 0.5f,
        typingStrength = 0.76f,
        happyStrength = 1f,
        errorStrength = 0.08f,
        submitStrength = 1f,
    )
    MiffanKind.SPROUT -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.LeafSway,
        cycleMillis = 2_100,
        idleStrength = 0.42f,
        thinkingStrength = 1f,
        focusedStrength = 0.72f,
        typingStrength = 0.9f,
        happyStrength = 1f,
        errorStrength = 0.32f,
        submitStrength = 1f,
    )
    MiffanKind.DUMPLING -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.DumplingRipple,
        cycleMillis = 2_400,
        idleStrength = 0.32f,
        thinkingStrength = 1f,
        focusedStrength = 0.56f,
        typingStrength = 1f,
        happyStrength = 1f,
        errorStrength = 0.12f,
        submitStrength = 1f,
    )
    MiffanKind.STARGAZER -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.StarTwinkle,
        cycleMillis = 2_400,
        idleStrength = 0.34f,
        thinkingStrength = 1f,
        focusedStrength = 0.52f,
        typingStrength = 0.82f,
        happyStrength = 1f,
        errorStrength = 0.1f,
        submitStrength = 1f,
        nightIdleMultiplier = 1.5f,
    )
}

internal fun MiffanKindBehavior.strengthFor(
    state: MiffanMascotState,
    inputState: MiffanMascotInputState,
    dayPhase: MiffanDayPhase,
    submitProgress: Float,
): Float {
    val semanticStrength = when (state) {
        MiffanMascotState.Thinking -> thinkingStrength
        MiffanMascotState.Happy -> happyStrength
        MiffanMascotState.Error -> errorStrength
        MiffanMascotState.UpdateAvailable -> happyStrength * 0.7f
        MiffanMascotState.Idle -> when (inputState) {
            MiffanMascotInputState.Inactive -> idleStrength * if (dayPhase == MiffanDayPhase.Night) {
                nightIdleMultiplier
            } else {
                1f
            }
            MiffanMascotInputState.Focused -> focusedStrength
            MiffanMascotInputState.Typing -> typingStrength
        }
    }
    val submitEnvelope = sin(submitProgress.coerceIn(0f, 1f) * Math.PI).toFloat()
    return maxOf(semanticStrength, submitStrength * submitEnvelope).coerceIn(0f, 1f)
}

internal fun MiffanKindBehavior.poseFor(
    phaseDegrees: Float,
    strength: Float,
    state: MiffanMascotState,
    inputState: MiffanMascotInputState,
): MiffanSignaturePose {
    if (state == MiffanMascotState.Error) return MiffanSignaturePose()

    val radians = Math.toRadians(phaseDegrees.toDouble())
    val wave = sin(radians).toFloat()
    return when (signature) {
        MiffanSignatureMotion.GrainHop -> {
            val hop = wave.coerceAtLeast(0f) * strength
            MiffanSignaturePose(
                offsetY = -9f * hop,
                scaleX = 1f + 0.035f * hop,
                scaleY = 1f - 0.025f * hop,
            )
        }
        MiffanSignatureMotion.LeafSway -> {
            val listening = if (
                state == MiffanMascotState.Idle && inputState != MiffanMascotInputState.Inactive
            ) {
                1f
            } else {
                0f
            }
            MiffanSignaturePose(
                offsetX = listening * 2.2f * strength,
                rotationDegrees = (wave * 4.5f + listening * 3.5f) * strength,
            )
        }
        MiffanSignatureMotion.DumplingRipple -> {
            val step = sin(radians).toFloat()
            MiffanSignaturePose(
                offsetX = step * 3f * strength,
                offsetY = -abs(wave) * 1.8f * strength,
                rotationDegrees = step * 1.8f * strength,
                scaleY = 1f + abs(sin(radians * 3.0).toFloat()) * 0.015f * strength,
            )
        }
        MiffanSignatureMotion.StarTwinkle -> {
            val hover = (cos(radians).toFloat() + 1f) / 2f
            MiffanSignaturePose(
                offsetY = -9f * hover * strength,
                rotationDegrees = wave * 2f * strength,
                scaleX = 1f + hover * 0.025f * strength,
                scaleY = 1f + hover * 0.025f * strength,
            )
        }
    }
}
