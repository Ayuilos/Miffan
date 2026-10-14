package me.ayuilos.miffan.data.ai.transformers

import me.ayuilos.miffan.data.ai.tools.imageIdForUrl
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Run before OCR so non-vision agents retain ids even after images are replaced by descriptions. */
object ImageIdTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        annotateImageIds(messages)
}

internal fun annotateImageIds(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    if (message.role != MessageRole.USER) message else message.copy(
        parts = buildList {
            message.parts.forEachIndexed { index, part ->
                add(part)
                if (part is UIMessagePart.Image) {
                    val annotation = UIMessagePart.Text("[image_id: ${imageIdForUrl(part.url)}]")
                    if (message.parts.getOrNull(index + 1) != annotation) add(annotation)
                }
            }
        }
    )
}
