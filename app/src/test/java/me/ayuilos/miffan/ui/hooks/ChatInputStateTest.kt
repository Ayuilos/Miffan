package me.ayuilos.miffan.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputStateTest {
    @Test
    fun `blank drafts cannot be sent or queued`() {
        val state = ChatInputState()
        assertTrue(state.isEmpty())
        state.setMessageText(" \n\t")
        assertTrue(state.isEmpty())
        state.setMessageText("next question")
        assertFalse(state.isEmpty())
    }

    @Test
    fun `attachments can be sent without text and clear with the draft`() {
        val state = ChatInputState()
        state.messageContent = listOf(
            UIMessagePart.Document(url = "file:///note.txt", fileName = "note.txt", mime = "text/plain")
        )
        assertFalse(state.isEmpty())
        state.clearInput()
        assertTrue(state.isEmpty())
    }
}
