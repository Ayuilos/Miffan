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

@Composable
internal fun WhaleThemeCard(
    themeApplied: Boolean,
    avatarApplied: Boolean,
    assistantName: String?,
    enabled: Boolean,
    onApplyTheme: () -> Unit,
    onApplyAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewClip by remember { mutableStateOf(WhaleGirlClip.IDLE) }
    var replayId by remember { mutableIntStateOf(0) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("蓝色大肥鱼", style = MaterialTheme.typography.titleLarge)
            Text(
                "只露小脑袋，也会认真陪你聊天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "爱吃饭、有点嘴硬。她会摸摸、扒饭、嚼饭，也会认真推理和打瞌睡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WhaleGirlClip.entries.chunked(4).forEach { clips ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    clips.forEach { clip ->
                        FilterChip(
                            selected = previewClip == clip,
                            onClick = { replayId++; previewClip = clip },
                            label = { Text(clip.previewName()) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WhaleThemePreview(dark = false, clip = previewClip, replayId = replayId, modifier = Modifier.weight(1f))
                WhaleThemePreview(dark = true, clip = previewClip, replayId = replayId, modifier = Modifier.weight(1f))
            }
            Button(
                onClick = onApplyTheme,
                enabled = enabled && !themeApplied,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (themeApplied) "已应用蓝鱼主题" else "应用蓝鱼主题")
            }
            Text(
                "浅色与深色跟随外观设置，应用后会关闭动态取色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (assistantName != null) {
                Text(
                    "当前助手：${assistantName.ifBlank { "未命名助手" }}",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(
                    onClick = onApplyAvatar,
                    enabled = enabled && !avatarApplied,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (avatarApplied) "当前助手已使用蓝鱼头像" else "当前助手使用蓝鱼头像")
                }
            }
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
                    if (dark) "深海蓝" else "晴空蓝",
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
                    "今天聊点什么？",
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

private fun WhaleGirlClip.previewName(): String = when (this) {
    WhaleGirlClip.IDLE -> "微笑"
    WhaleGirlClip.PETTING -> "摸摸"
    WhaleGirlClip.SUCCESS -> "开心"
    WhaleGirlClip.SURPRISE -> "提醒"
    WhaleGirlClip.EATING -> "扒饭"
    WhaleGirlClip.CHEWING -> "嚼饭"
    WhaleGirlClip.THINKING -> "推理"
    WhaleGirlClip.SLEEPING -> "睡觉"
}
