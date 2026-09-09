package me.ayuilos.miffan.ui.pages.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.ui.components.ui.WhaleGirlClip
import me.ayuilos.miffan.ui.components.ui.WhaleGirlLineArtPortrait
import me.ayuilos.miffan.ui.theme.presets.WhaleThemePreset

/** Local preview controls only: never changes an assistant or the app's appearance. */
@Composable
internal fun WhaleGirlLabPage(active: Boolean) {
    var clip by rememberSaveable { mutableStateOf(WhaleGirlClip.IDLE) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var reduced by rememberSaveable { mutableStateOf(false) }
    var returnToIdle by rememberSaveable { mutableStateOf(false) }
    var replayId by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var inspectTime by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableStateOf(0f) }
    var size by rememberSaveable { mutableIntStateOf(168) }
    fun replay(next: WhaleGirlClip = clip) {
        inspectTime = false
        seconds = 0f
        clip = next
        replayId++
        finished = false
        playing = true
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("蓝色大肥鱼 · 动作实验室", style = MaterialTheme.typography.titleLarge)
        Text("点选神态可重放；所有控制只影响此预览。", style = MaterialTheme.typography.bodySmall)
        MaterialTheme(colorScheme = WhaleThemePreset.getColorScheme(dark)) {
            Box(
                Modifier.fillMaxWidth().height(300.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                WhaleGirlLineArtPortrait(
                    clip = clip,
                    dark = dark,
                    playing = playing && active && !inspectTime,
                    previewSeconds = if (inspectTime) seconds else null,
                    reducedMotion = reduced,
                    replayId = replayId,
                    onPlaybackFinished = {
                        finished = true
                        if (returnToIdle) replay(WhaleGirlClip.IDLE)
                    },
                    modifier = Modifier.size(size.dp),
                )
            }
        }
        Text(
            "${clip.labName()} · ${size} dp · " + when {
                inspectTime -> "时间点：${"%.2f".format(seconds)} 秒"
                !playing -> "已暂停"
                reduced -> "减弱动态（静态神态）"
                finished -> "播放结束，保留神态"
                clip.looping -> "循环播放"
                else -> "单次动作"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        WhaleGirlClip.entries.chunked(4).forEach { clips ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                clips.forEach { option ->
                    FilterChip(
                        selected = clip == option,
                        onClick = { replay(option) },
                        label = { Text(option.labName()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (!playing && finished) replay() else playing = !playing }) {
                Text(if (playing) "暂停" else "播放")
            }
            OutlinedButton(onClick = { replay() }) { Text("重放") }
            OutlinedButton(onClick = { replay(WhaleGirlClip.IDLE) }) { Text("回到微笑") }
        }
        LabSwitch("动作时间检查", inspectTime) { inspectTime = it }
        if (inspectTime) {
            Text("拖动查看送饭、吞咽和呼吸的中间姿态。关闭减弱动态可查看完整动作。",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 0f..6f)
        }
        LabSwitch("夜间配色", dark) { dark = it }
        LabSwitch("减弱动态", reduced) { reduced = it }
        LabSwitch("单次动作结束后回到微笑", returnToIdle) { returnToIdle = it }
        Text("头像尺寸", style = MaterialTheme.typography.titleSmall)
        listOf(28, 32, 40, 80, 168, 280).chunked(3).forEach { sizes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sizes.forEach { option ->
                    FilterChip(
                        selected = size == option,
                        onClick = { size = option },
                        label = { Text("$option dp") },
                    )
                }
            }
        }
        Text(
            "聚焦 → 低头关注；打字 → 视线跟随；发送 → 点头收到。等待回复 → 吃饭；输出正文 → 咀嚼；实际推理 → 思考。收到、摸摸、得意、提醒为单次动作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun WhaleGirlClip.labName(): String = when (this) {
    WhaleGirlClip.IDLE -> "微笑"
    WhaleGirlClip.FOCUSED -> "聚焦"
    WhaleGirlClip.TYPING -> "打字"
    WhaleGirlClip.SUBMITTED -> "收到"
    WhaleGirlClip.PETTING -> "摸摸"
    WhaleGirlClip.SUCCESS -> "得意"
    WhaleGirlClip.SURPRISE -> "提醒"
    WhaleGirlClip.EATING -> "吃饭"
    WhaleGirlClip.CHEWING -> "咀嚼"
    WhaleGirlClip.THINKING -> "思考"
    WhaleGirlClip.SLEEPING -> "睡觉"
}
