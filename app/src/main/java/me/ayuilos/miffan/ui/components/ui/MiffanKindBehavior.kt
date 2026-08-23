package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.Immutable
import me.ayuilos.miffan.data.model.MiffanKind
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

fun MiffanKind.miffanKindBehavior(): MiffanKindBehavior = when (this) {
    MiffanKind.RICE -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.GrainHop,
        cycleMillis = 1_680,
        idleStrength = 0.14f,
        thinkingStrength = 0.78f,
        focusedStrength = 0.24f,
        typingStrength = 0.42f,
        happyStrength = 0.72f,
        errorStrength = 0.1f,
        submitStrength = 0.9f,
    )
    MiffanKind.SPROUT -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.LeafSway,
        cycleMillis = 2_100,
        idleStrength = 0.3f,
        thinkingStrength = 0.72f,
        focusedStrength = 0.5f,
        typingStrength = 0.68f,
        happyStrength = 0.86f,
        errorStrength = 0.3f,
        submitStrength = 0.8f,
    )
    MiffanKind.DUMPLING -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.DumplingRipple,
        cycleMillis = 1_320,
        idleStrength = 0.12f,
        thinkingStrength = 0.88f,
        focusedStrength = 0.26f,
        typingStrength = 0.74f,
        happyStrength = 0.94f,
        errorStrength = 0.18f,
        submitStrength = 1f,
    )
    MiffanKind.STARGAZER -> MiffanKindBehavior(
        signature = MiffanSignatureMotion.StarTwinkle,
        cycleMillis = 2_400,
        idleStrength = 0.2f,
        thinkingStrength = 0.86f,
        focusedStrength = 0.34f,
        typingStrength = 0.6f,
        happyStrength = 0.82f,
        errorStrength = 0.16f,
        submitStrength = 0.92f,
        nightIdleMultiplier = 1.45f,
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
