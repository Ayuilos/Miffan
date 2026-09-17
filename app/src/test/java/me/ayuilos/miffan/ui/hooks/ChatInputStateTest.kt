package me.ayuilos.miffan.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateTest {
    @Test
    fun `image-only draft has no empty text part`() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///image.png")
        state.messageContent = listOf(image)
        state.setMessageText(" \n")
        assertEquals(listOf(image), state.getContents())
        assertFalse(state.isEmpty())
    }

    @Test
    fun `editing image-only message can add text without losing attachments`() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///image.png")
        state.setContents(listOf(image))
        state.editingMessage = Uuid.random()
        state.setMessageText("Describe this")
        assertEquals(listOf(UIMessagePart.Text("Describe this"), image), state.getContents())
    }

    @Test
    fun `removing caption keeps image without blank text parts`() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///image.png")
        state.setContents(listOf(UIMessagePart.Text(" "), image, UIMessagePart.Text("caption")))
        state.editingMessage = Uuid.random()
        state.setMessageText("")
        assertEquals(listOf(image), state.getContents())
        assertFalse(state.isEmpty())
    }

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
