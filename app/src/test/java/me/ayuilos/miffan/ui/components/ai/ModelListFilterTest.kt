package me.ayuilos.miffan.ui.components.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelListFilterTest {
    private val text = Model(modelId = "gpt-5", type = ModelType.CHAT)
    private val drawingChat = Model(modelId = "google/gemini-2.5-flash-image", type = ModelType.CHAT,
        outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private val imagesApi = Model(modelId = "gpt-image-1", type = ModelType.IMAGE, outputModalities = listOf(Modality.IMAGE))

    @Test fun chatPickersKeepDrawingChatModelsUnlessTextOnly() {
        assertTrue(text.listedFor(ModelType.CHAT, textOnly = false))
        assertTrue(drawingChat.listedFor(ModelType.CHAT, textOnly = false))
        assertTrue(text.listedFor(ModelType.CHAT, textOnly = true))
        assertFalse(drawingChat.listedFor(ModelType.CHAT, textOnly = true))
        assertFalse(imagesApi.listedFor(ModelType.CHAT, textOnly = false))
        assertTrue(imagesApi.listedFor(ModelType.IMAGE, textOnly = false))
    }
}
