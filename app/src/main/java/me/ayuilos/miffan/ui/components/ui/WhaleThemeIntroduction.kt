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
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R

private val introductionClips = listOf(
    WhaleGirlClip.IDLE to R.string.whale_intro_chat,
    WhaleGirlClip.EATING to R.string.whale_intro_rice,
    WhaleGirlClip.THINKING to R.string.whale_intro_think,
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
    val (clip, previewLabelRes) = introductionClips[selectedPreview]
    val previewDescription = stringResource(R.string.whale_intro_preview_description, stringResource(previewLabelRes))
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
                    Text(stringResource(R.string.whale_intro_title), style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center)
                    Text(stringResource(R.string.whale_intro_subtitle), style = MaterialTheme.typography.bodyLarge,
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
                                contentDescription = previewDescription
                            },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        introductionClips.forEachIndexed { index, (_, label) ->
                            FilterChip(
                                selected = selectedPreview == index,
                                onClick = { selectedPreview = index },
                                enabled = !busy,
                                label = { Text(stringResource(label)) },
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
                        Text(stringResource(R.string.whale_intro_change_icon), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.whale_intro_description),
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
                                Text(stringResource(R.string.whale_intro_applying))
                            }
                        } else {
                            Text(stringResource(if (errorMessage.isNullOrBlank()) R.string.whale_intro_try_now else R.string.whale_intro_retry))
                        }
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                        Text(stringResource(R.string.whale_intro_later))
                    }
                }
            }
        }
    }
}
