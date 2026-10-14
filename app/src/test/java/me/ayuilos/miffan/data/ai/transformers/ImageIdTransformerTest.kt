package me.ayuilos.miffan.data.ai.transformers

import me.ayuilos.miffan.data.ai.tools.imageIdForUrl
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ImageIdTransformerTest {
    @Test fun annotatesOnlyUserImagesAndLeavesOriginalMessagesUntouched() {
        val image = UIMessagePart.Image("file:///uploads/reference.png")
        val originalParts = listOf(UIMessagePart.Text("Edit this"), image, image.copy(url = "file:///second.png"))
        val user = UIMessage(role = MessageRole.USER, parts = originalParts)
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(image))
        val system = UIMessage(role = MessageRole.SYSTEM, parts = listOf(image))
        val annotated = annotateImageIds(listOf(user, assistant, system))
        assertEquals(originalParts, user.parts)
        assertEquals(3, user.parts.size)
        assertEquals(5, annotated.first().parts.size)
        assertEquals(image, annotated.first().parts[1])
        assertEquals(UIMessagePart.Text("[image_id: ${imageIdForUrl(image.url)}]"), annotated.first().parts[2])
        assertSame(assistant, annotated[1])
        assertSame(system, annotated[2])
        assertEquals(annotated, annotateImageIds(annotated))
        // OCR substitutes each Image part with Text, leaving the following annotation intact.
        val afterOcr = annotated.first().parts.map { if (it is UIMessagePart.Image) UIMessagePart.Text("[Image]") else it }
        assertTrue(afterOcr.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("[image_id:") })
    }
}
