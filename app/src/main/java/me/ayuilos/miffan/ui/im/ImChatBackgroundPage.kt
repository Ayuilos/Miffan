package me.ayuilos.miffan.ui.im

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.chat.AssistantBackground
import me.ayuilos.miffan.ui.pages.chat.MeshGradientBackground
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Tick02
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private enum class ChatBackgroundKind { PLAIN, GRADIENT, IMAGE }

private val Assistant.backgroundKind get() = when {
    useGradientBackground -> ChatBackgroundKind.GRADIENT
    background != null -> ChatBackgroundKind.IMAGE
    else -> ChatBackgroundKind.PLAIN
}

/** Easy chat's own background page: a live preview of the chat and three choices, nothing else. */
@Composable
fun ImChatBackgroundPage(assistantId: String, vm: ImPartnerVM = koinViewModel(key = assistantId, parameters = { parametersOf(Uuid.parse(assistantId)) })) {
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    fun save(transform: (Assistant) -> Assistant) { scope.launch { vm.update(transform) } }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val local = uri?.let { filesManager.createChatFilesByContents(listOf(it)).firstOrNull() } ?: return@rememberLauncherForActivityResult
        save { it.copy(background = local.toString(), useGradientBackground = false) }
    }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_p5_background)) }) { padding ->
        val partner = assistant
        if (partner == null) {
            ImEmpty(stringResource(R.string.im_p5_missing_partner))
            return@Scaffold
        }
        val kind = partner.backgroundKind
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ChatBackgroundPreview(partner, Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatBackgroundChoice(stringResource(R.string.im_background_plain), kind == ChatBackgroundKind.PLAIN,
                    onClick = { save { it.copy(background = null, useGradientBackground = false) } }) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
                ChatBackgroundChoice(stringResource(R.string.im_background_gradient), kind == ChatBackgroundKind.GRADIENT,
                    onClick = { save { it.copy(useGradientBackground = true) } }) {
                    MeshGradientBackground(Modifier.fillMaxSize())
                }
                ChatBackgroundChoice(stringResource(R.string.im_background_image), kind == ChatBackgroundKind.IMAGE,
                    // A saved image comes back when chosen again; otherwise choosing means picking one.
                    onClick = { if (partner.background != null) save { it.copy(useGradientBackground = false) } else pickImage.launch("image/*") }) {
                    if (partner.background != null) {
                        AsyncImage(partner.background, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(HugeIcons.Image01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (kind == ChatBackgroundKind.IMAGE) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val opacity = partner.backgroundOpacity.coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.im_background_strength), Modifier.weight(1f))
                            Text("${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(value = opacity, valueRange = 0.1f..1f, steps = 8,
                            onValueChange = { value -> save { it.copy(backgroundOpacity = (value * 10).roundToInt() / 10f) } })
                    }
                }
                OutlinedButton(onClick = { pickImage.launch("image/*") }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.im_background_change_image))
                }
            }
        }
    }
}

/** A small copy of the chat with the chosen background, so a choice shows how messages will read on it. */
@Composable
private fun ChatBackgroundPreview(assistant: Assistant, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(0.75f).clip(shape)
        .background(MaterialTheme.colorScheme.background)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        AssistantBackground(assistant, Modifier.fillMaxSize())
        // The thread's header capsule, in miniature.
        Surface(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistantAvatar(assistant.name, assistant.avatar, Modifier.size(24.dp))
                Text(threadAssistantName(assistant), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            PreviewBubble(stringResource(R.string.im_background_preview_partner), fromUser = false)
            PreviewBubble(stringResource(R.string.im_background_preview_user), fromUser = true)
            PreviewBubble(stringResource(R.string.im_background_preview_reply), fromUser = false)
        }
    }
}

@Composable
private fun ColumnScope.PreviewBubble(text: String, fromUser: Boolean) {
    // Same colors and corners as the thread's message bubbles.
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.align(if (fromUser) Alignment.End else Alignment.Start).widthIn(max = 200.dp)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RowScope.ChatBackgroundChoice(label: String, selected: Boolean, onClick: () -> Unit, thumbnail: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { role = Role.RadioButton; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onClick, shape = shape, color = Color.Transparent,
            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)) {
            Box(Modifier.clip(shape)) {
                thumbnail()
                if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Tick02, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
