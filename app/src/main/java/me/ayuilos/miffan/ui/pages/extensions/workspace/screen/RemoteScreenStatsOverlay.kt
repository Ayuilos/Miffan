package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.workspace.screen.RfbPixelFormat

/**
 * Diagnostic numbers for tuning the screen: what the session decodes versus what the canvas
 * draws. Toggled by long-pressing the connection line in the top bar; never shown by default.
 */
@Composable
internal fun RemoteScreenStatsOverlay(vm: RemoteScreenVM, modifier: Modifier = Modifier) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val rdp by vm.rdpStats.collectAsStateWithLifecycle()
    var drawnFps by remember { mutableDoubleStateOf(0.0) }
    var receiveRate by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(vm) {
        var count = vm.framesDrawn.get()
        var received = vm.rdpStats.value?.bytesReceived ?: 0L
        var at = System.nanoTime()
        while (true) {
            delay(1_000)
            val nowCount = vm.framesDrawn.get()
            val nowReceived = vm.rdpStats.value?.bytesReceived ?: 0L
            val now = System.nanoTime()
            drawnFps = (nowCount - count) * 1e9 / (now - at)
            receiveRate = (nowReceived - received).coerceAtLeast(0) * 1e9 / (now - at)
            count = nowCount
            received = nowReceived
            at = now
        }
    }
    val s = stats
    // Before RDP finishes its handshake the stats hold placeholders; show the waiting line instead.
    val r = rdp?.takeIf { it.security != "Unknown" }
    val text = if (r != null) buildString {
        // RDP is paced and encoded by the server; the stages below show where a frame spends its time.
        append("RDP · %s · %s · %d×%d".format(r.security, r.encoding, r.width, r.height))
        append("\n%.1f fps · 解码 %.1f fps · 绘制 %.1f fps".format(r.framesPerSecond, r.decodeFramesPerSecond, drawnFps))
        append("\n解码 均 %.0f / 峰 %.0f ms · 未出帧 %.0f ms · 超时 %d".format(
            r.decodeMeanMs, r.decodeMaxMs, r.pendingDecodeMs, r.outputWaitTimeouts))
        append("\n转换 %.0f · 复制 %.0f · 至确认 %.0f · 确认排队 %.0f ms".format(
            r.yuvToRgbMeanMs, r.sinkMeanMs, r.surfaceToAckMeanMs, r.ackQueueMeanMs))
        append("\n接收 %s/s · 在途 %d / 峰 %d".format(kb(receiveRate), r.inFlightFrames, r.peakInFlightFrames))
        append("\n%s%s · 低延迟 %s".format(r.decoder ?: "—",
            when (r.h264HardwareAccelerated) { true -> "（硬件）"; false -> "（软件）"; null -> "" },
            when (r.lowLatencySupported) { true -> "支持"; false -> "不支持"; null -> "—" }))
        append("\n已配置 %s".format(r.decoderConfiguration))
    } else if (s == null) "等待连接…" else buildString {
        append("%.1f fps · %s/s · %s".format(s.fps, kb(s.bytesPerSecond), s.encodings.joinToString("+").ifEmpty { "—" }))
        if (s.scale > 1) append(" · ${s.scale}× 降采样")
        if (s.pixelFormat == RfbPixelFormat.RGB565) append(" · 16 位色")
        append("\n每帧 %s（最大 %s）· 空帧 %d".format(kb(s.avgUpdateBytes), kb(s.maxUpdateBytes.toDouble()), s.emptyUpdates))
        append("\n延迟 %.0f ms · 在途请求 %d".format(s.avgLatencyMillis, s.outstandingRequests))
        append("\n读 %.1f · 解码 %.1f · 缩放 %.1f · 上传 %.1f ms".format(
            s.avgReadMillis, s.avgDecodeMillis, s.avgScaleMillis, s.avgSinkMillis))
        append("\n变化 %.2f MP · 绘制 %.1f fps".format(s.avgChangedPixels / 1e6, drawnFps))
    }
    Text(text, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
        modifier = modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp))
}

private fun kb(bytes: Double): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024 * 1024)) else "%.0f KB".format(bytes / 1024)
