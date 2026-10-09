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
    LaunchedEffect(vm) {
        var count = vm.framesDrawn.get()
        var at = System.nanoTime()
        while (true) {
            delay(1_000)
            val nowCount = vm.framesDrawn.get()
            val now = System.nanoTime()
            drawnFps = (nowCount - count) * 1e9 / (now - at)
            count = nowCount
            at = now
        }
    }
    val s = stats
    val r = rdp
    val text = if (r != null) buildString {
        // RDP is paced and encoded by the server; what matters here is the codec and who decodes it.
        append("RDP · %s · %s".format(r.security, r.encoding))
        append("\n%.1f fps · 共 %d 帧 · 绘制 %.1f fps".format(r.framesPerSecond, r.frames, drawnFps))
        append("\n解码器 %s%s".format(r.decoder ?: "—", when (r.h264HardwareAccelerated) { true -> "（硬件）"; false -> "（软件）"; null -> "" }))
        append("\n已收 %s · 已发 %s".format(kb(r.bytesReceived.toDouble()), kb(r.bytesSent.toDouble())))
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
