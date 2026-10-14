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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn as heightAtLeast
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.tools.GENERATE_IMAGE_TOOL
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.pages.setting.ImageToolModelSheet
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.ToolApprovalState
import org.koin.compose.koinInject
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
    isImageGeneration() && approvalState !is ToolApprovalState.Pending && approvalState !is ToolApprovalState.Denied &&
        (generatedImages().isNotEmpty() || (streaming && !isExecuted))

/** The tool ran before drawing was set up; the bubble offers to turn it on (contract: `setup_required`). */
internal fun UIMessagePart.Tool.needsDrawingSetup(): Boolean {
    val text = output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return false
    return runCatching {
        Json.parseToJsonElement(text).jsonObject["setup_required"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)
}

@Composable
internal fun ThreadGeneratedImages(tool: UIMessagePart.Tool, streaming: Boolean) {
    if (tool.needsDrawingSetup()) return
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

/**
 * A drawing paused because no image model is chosen yet. Picking one approves this very call, so the same
 * reply carries on into the drawing; "not now" declines it like any permission.
 */
@Composable
internal fun ThreadDrawingSetupCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val settings = LocalSettings.current
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    val model = settings.imageToolModelOrNull()

    fun approveOnceSaved() {
        answered = true
        scope.launch {
            // The call runs with the stored settings, so wait for the choice to land before resuming it.
            withTimeoutOrNull(3_000) { settingsStore.settingsFlow.first { it.imageToolModelOrNull() != null } }
            onApproval(tool.toolCallId, true)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Image03, null, Modifier.size(22.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (model != null) "画图已开启" else "画图还没开启", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (model != null) {
                            listOfNotNull(model.displayName.ifBlank { model.modelId }, model.findProvider(settings.providers)?.name)
                                .joinToString(" · ")
                        } else "选一个画图模型，所有伙伴都能帮你画图、改图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !answered,
                    onClick = { if (model != null) approveOnceSaved() else picking = true },
                    modifier = Modifier.weight(1f).heightAtLeast(min = 44.dp),
                ) { Text(if (model != null) "开始画图" else "选择画图模型") }
                OutlinedButton(
                    enabled = !answered,
                    onClick = { answered = true; onApproval(tool.toolCallId, false) },
                    modifier = Modifier.heightAtLeast(min = 44.dp),
                ) { Text("先不用") }
            }
        }
    }

    if (picking) {
        ImageToolModelSheet(
            settings = settings,
            onUpdate = { updated ->
                scope.launch { settingsStore.update(updated) }
                if (updated.imageToolModelOrNull() != null) approveOnceSaved()
            },
            onDismiss = { picking = false },
            easy = true,
        )
    }
}
