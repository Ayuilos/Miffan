package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.characterMotionProfileOrDefault
import me.ayuilos.miffan.data.model.miffanAppearanceOrDefault

/** Leave enough time for the authored reply animation, including its transition. */
fun Avatar.characterReplyHoldMillis(): Long = when (this) {
    is Avatar.WhaleGirl -> WhaleGirlClip.SUCCESS.durationMillis + 200L
    else -> 900L
}

/** Shared scene boundary: pages supply meaning, each character owns its drawing. */
@Composable
fun AssistantCharacterMascot(
    avatar: Avatar,
    state: MiffanMascotState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    presentation: MiffanPresentation = MiffanPresentation.Scene,
    interactive: Boolean = false,
    attentionTarget: Offset? = null,
    attentionId: Int = 0,
    inputState: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    submitId: Int = 0,
    dayPhase: MiffanDayPhase = MiffanDayPhase.Noon,
    generationPhase: AssistantGenerationPhase = AssistantGenerationPhase.None,
) {
    when (avatar) {
        is Avatar.WhaleGirl -> WhaleGirlMascot(
            state = state,
            modifier = modifier,
            reducedMotion = reducedMotion,
            presentation = presentation,
            interactive = interactive,
            attentionTarget = attentionTarget,
            attentionId = attentionId,
            inputState = inputState,
            submitId = submitId,
            dayPhase = dayPhase,
            generationPhase = generationPhase,
        )
        is Avatar.Miffan, Avatar.Dummy -> MiffanMascot(
            state = state,
            modifier = modifier,
            appearance = avatar.miffanAppearanceOrDefault(),
            motionProfile = avatar.characterMotionProfileOrDefault(),
            reducedMotion = reducedMotion,
            presentation = presentation,
            interactive = interactive,
            attentionTarget = attentionTarget,
            attentionId = attentionId,
            inputState = inputState,
            submitId = submitId,
            dayPhase = dayPhase,
        )
        else -> UIAvatar(name = "", value = avatar, modifier = modifier)
    }
}
