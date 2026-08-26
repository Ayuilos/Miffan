package me.ayuilos.miffan.ui.pages.chat

import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMascotStateTest {
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
