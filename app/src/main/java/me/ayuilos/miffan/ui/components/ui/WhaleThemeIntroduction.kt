package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private val introductionClips = listOf(
    WhaleGirlClip.IDLE to "陪你聊天",
    WhaleGirlClip.EATING to "吃白饭",
    WhaleGirlClip.THINKING to "认真想想",
)

/** Presentation only: the host owns eligibility, persistence and applying the collection. */
@Composable
internal fun WhaleThemeIntroduction(
    onTryTheme: (changeLauncherIcon: Boolean) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
) {
    var selectedPreview by rememberSaveable { mutableIntStateOf(0) }
    var changeLauncherIcon by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = rememberMiffanReducedMotion()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val (clip, previewLabel) = introductionClips[selectedPreview]
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(160).dp

    LaunchedEffect(selectedPreview, reducedMotion, busy, lifecycle) {
        if (!reducedMotion && !busy) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(4_000)
                selectedPreview = (selectedPreview + 1) % introductionClips.size
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Card(Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight)) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("蓝色大肥鱼来啦", style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                    Text("陪你聊天，也陪你吃白饭。", style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center)

                    // A single keyed player releases the outgoing layer before selecting
                    // another clip; this introduction never preloads three large atlases.
                    key(clip) {
                        WhaleGirlAnimatedPortrait(
                            clip = clip,
                            playing = !busy,
                            posterResourceId = whaleGirlPoster(clip),
                            reducedMotion = reducedMotion,
                            modifier = Modifier.size(160.dp).semantics {
                                contentDescription = "蓝色大肥鱼，$previewLabel"
                            },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        introductionClips.forEachIndexed { index, (_, label) ->
                            FilterChip(
                                selected = selectedPreview == index,
                                onClick = { selectedPreview = index },
                                enabled = !busy,
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = changeLauncherIcon,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { changeLauncherIcon = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = changeLauncherIcon, onCheckedChange = null, enabled = !busy)
                        Text("同时换上大肥鱼图标", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("换上蓝鱼配色，并创建专属大肥鱼助手。已有助手保持原样，新助手的名字、性格和配置都可编辑或重置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (!errorMessage.isNullOrBlank()) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Button(onClick = { if (!busy) onTryTheme(changeLauncherIcon) },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在应用")
                            }
                        } else {
                            Text(if (errorMessage.isNullOrBlank()) "立即体验" else "重试")
                        }
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                        Text("以后再说")
                    }
                }
            }
        }
    }
}
