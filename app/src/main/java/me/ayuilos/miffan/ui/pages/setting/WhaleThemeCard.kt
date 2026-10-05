package me.ayuilos.miffan.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.ui.components.ui.WhaleGirlAnimatedPortrait
import me.ayuilos.miffan.ui.components.ui.WhaleGirlClip
import me.ayuilos.miffan.ui.components.ui.whaleGirlPoster
import me.ayuilos.miffan.ui.components.ui.rememberMiffanReducedMotion
import me.ayuilos.miffan.ui.theme.presets.WhaleThemePreset
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R

@Composable
internal fun WhaleThemeCard(
    themeApplied: Boolean,
    enabled: Boolean,
    onApplyTheme: () -> Unit,
    modifier: Modifier = Modifier,
    onTryTheme: (() -> Unit)? = null,
    onRestoreTheme: (() -> Unit)? = null,
) {
    var previewClip by remember { mutableStateOf(WhaleGirlClip.IDLE) }
    var replayId by remember { mutableIntStateOf(0) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.whale_name), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.whale_card_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.whale_card_personality),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WhaleGirlClip.entries.chunked(4).forEach { clips ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    clips.forEach { clip ->
                        FilterChip(
                            selected = previewClip == clip,
                            onClick = { replayId++; previewClip = clip },
                            label = { Text(stringResource(clip.previewName())) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WhaleThemePreview(dark = false, clip = previewClip, replayId = replayId, modifier = Modifier.weight(1f))
                WhaleThemePreview(dark = true, clip = previewClip, replayId = replayId, modifier = Modifier.weight(1f))
            }
            onTryTheme?.let { tryTheme ->
                Button(onClick = tryTheme, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.whale_try_theme))
                }
                Text(
                    stringResource(R.string.whale_try_theme_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            onRestoreTheme?.let { restoreTheme ->
                OutlinedButton(onClick = restoreTheme, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.whale_restore_colors))
                }
            }
            Button(
                onClick = onApplyTheme,
                enabled = enabled && !themeApplied,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (themeApplied) R.string.whale_theme_applied else R.string.whale_apply_theme))
            }
            Text(
                stringResource(R.string.whale_theme_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        }
    }
}

@Composable
private fun WhaleThemePreview(dark: Boolean, clip: WhaleGirlClip, replayId: Int, modifier: Modifier = Modifier) {
    val reduced = rememberMiffanReducedMotion()
    MaterialTheme(colorScheme = WhaleThemePreset.getColorScheme(dark)) {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(if (dark) R.string.whale_palette_deep_sea else R.string.whale_palette_clear_sky),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WhaleGirlAnimatedPortrait(
                    clip = clip,
                    playing = true,
                    posterResourceId = whaleGirlPoster(clip),
                    replayId = replayId,
                    reducedMotion = reduced,
                    modifier = Modifier.size(80.dp),
                )
                Text(
                    stringResource(R.string.whale_preview_greeting),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@StringRes
private fun WhaleGirlClip.previewName(): Int = when (this) {
    WhaleGirlClip.IDLE -> R.string.whale_clip_idle
    WhaleGirlClip.FOCUSED -> R.string.whale_clip_focused
    WhaleGirlClip.TYPING -> R.string.whale_clip_typing
    WhaleGirlClip.SUBMITTED -> R.string.whale_clip_submitted
    WhaleGirlClip.PETTING -> R.string.whale_clip_petting
    WhaleGirlClip.SUCCESS -> R.string.whale_clip_success
    WhaleGirlClip.SURPRISE -> R.string.whale_clip_surprise
    WhaleGirlClip.EATING -> R.string.whale_clip_eating
    WhaleGirlClip.CHEWING -> R.string.whale_clip_chewing
    WhaleGirlClip.THINKING -> R.string.whale_clip_thinking
    WhaleGirlClip.SLEEPING -> R.string.whale_clip_sleeping
}
