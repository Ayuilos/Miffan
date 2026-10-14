package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.ai.tools.GENERATE_IMAGE_TOOL
import me.ayuilos.miffan.ui.components.richtext.ZoomableAsyncImage
import me.ayuilos.miffan.ui.modifier.shimmer
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image03

internal fun UIMessagePart.Tool.isImageGeneration(): Boolean = toolName == GENERATE_IMAGE_TOOL

internal fun UIMessagePart.Tool.generatedImages(): List<UIMessagePart.Image> =
    output.filterIsInstance<UIMessagePart.Image>()

/**
 * Easy mode treats a drawing as part of the answer, not as process: the bubble holds a placeholder while
 * the image is drawn and the image itself afterwards. A failed drawing shows nothing; the reply explains it.
 */
internal fun UIMessagePart.Tool.showsInThread(streaming: Boolean): Boolean =
    isImageGeneration() && (generatedImages().isNotEmpty() || (streaming && !isExecuted))

@Composable
internal fun ThreadGeneratedImages(tool: UIMessagePart.Tool, streaming: Boolean) {
    val images = tool.generatedImages()
    if (images.isEmpty()) {
        if (streaming && !tool.isExecuted) DrawingPlaceholder()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { image ->
            ZoomableAsyncImage(
                model = image.url,
                contentDescription = "画好的图片",
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .heightIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun DrawingPlaceholder() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(Modifier.shimmer(isLoading = true), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HugeIcons.Image03, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("正在画图…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
