package me.ayuilos.miffan.ui.pages.chat

import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.MiffanMascotInputState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMascotStateTest {
    @Test
    fun activeRetryAndInputTakePriorityOverBackgroundReminders() {
        assertEquals(MiffanMascotState.Thinking, resolveChatMascotState(
            hasErrors = true, semanticState = MiffanMascotState.UpdateAvailable, loading = true,
            showingCompletion = true, inputState = MiffanMascotInputState.Typing,
        ))
        assertEquals(MiffanMascotState.Idle, resolveChatMascotState(
            hasErrors = false, semanticState = MiffanMascotState.UpdateAvailable,
            inputState = MiffanMascotInputState.Typing,
        ))
        assertEquals(MiffanMascotState.Error, resolveChatMascotState(
            hasErrors = true, semanticState = MiffanMascotState.UpdateAvailable, showingCompletion = true,
        ))
    }

    @Test
    fun availableUpdateReachesChatMascot() {
        assertEquals(
            MiffanMascotState.UpdateAvailable,
            resolveChatMascotState(
                hasErrors = false,
                semanticState = MiffanMascotState.UpdateAvailable,
            ),
        )
    }

    @Test
    fun chatErrorTakesPriorityOverAvailableUpdate() {
        assertEquals(
            MiffanMascotState.Error,
            resolveChatMascotState(
                hasErrors = true,
                semanticState = MiffanMascotState.UpdateAvailable,
            ),
        )
    }
}
