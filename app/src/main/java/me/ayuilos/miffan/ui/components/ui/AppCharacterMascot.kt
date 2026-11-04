package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.ayuilos.miffan.data.model.appCharacterAvatar
import me.ayuilos.miffan.ui.context.LocalSettings

/** The app's own character in the given state: the whale under her role theme, otherwise the bowl. */
@Composable
fun AppCharacterMascot(
    state: MiffanMascotState,
    modifier: Modifier = Modifier,
    presentation: MiffanPresentation = MiffanPresentation.Scene,
    generationPhase: AssistantGenerationPhase = AssistantGenerationPhase.None,
) {
    val settings = LocalSettings.current
    val avatar = remember(settings.themeId, settings.dynamicColor) { settings.appCharacterAvatar() }
    AssistantCharacterMascot(
        avatar = avatar,
        state = state,
        modifier = modifier,
        presentation = presentation,
        generationPhase = generationPhase,
    )
}
