package me.ayuilos.miffan.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Small flat palette: blue hair, deeper fins/bow, pale face and white frills. */
internal data class WhaleLinePalette(
    val ink: Color, val paper: Color,
    val hair: Color = Color(0xFF58ACF5),
    val fin: Color = Color(0xFF247DDD),
    val accent: Color = Color(0xFF165BCD),
    val frill: Color = Color(0xFFF7FBFF),
) {
    companion object {
        val Day = WhaleLinePalette(Color(0xFF163D78), Color(0xFFFFF8EE))
        val Night = WhaleLinePalette(Color(0xFF152E58), Color(0xFFF5E3D9),
            Color(0xFF4F98EA), Color(0xFF327DD3), Color(0xFF8DCCFF), Color(0xFFEDF5FF))
    }
}

/** Shared native portrait: cached contours and a foreground-only expression clock. */
@Composable
internal fun WhaleGirlLineArtPortrait(
    clip: WhaleGirlClip,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    playing: Boolean = false,
    reducedMotion: Boolean = false,
    // Deterministic presentation time for previews and device capture, never a second animation clock.
    previewSeconds: Float? = null,
    replayId: Int = 0,
    onPlaybackFinished: (() -> Unit)? = null,
    attentionTarget: Offset? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember(clip, replayId) { mutableDoubleStateOf(0.0) }
    var finished by remember(clip, replayId) { androidx.compose.runtime.mutableStateOf(false) }
    val onFinished by rememberUpdatedState(onPlaybackFinished)
    LaunchedEffect(clip, replayId, playing, reducedMotion, lifecycle, previewSeconds, onPlaybackFinished != null) {
        if (previewSeconds != null || finished) return@LaunchedEffect
        // A finite reaction clock preserves completion callbacks even with reduced motion.
        // Reduced drawings never read seconds; paused/quiet loops schedule no frames.
        if (!playing || (reducedMotion && (clip.looping || onFinished == null))) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = withFrameNanos { it }
            while (!finished) {
                withFrameNanos { now ->
                    val elapsed = (now - previous) / 1_000_000_000.0
                    seconds = if (clip.looping) seconds + elapsed
                        else (seconds + elapsed).coerceAtMost(clip.durationMillis / 1000.0)
                    previous = now
                    if (!clip.looping && seconds >= clip.durationMillis / 1000f) {
                        finished = true
                        onFinished?.invoke()
                    }
                }
            }
        }
    }
    val closed by whaleShapeState(
        when (clip) {
            WhaleGirlClip.PETTING, WhaleGirlClip.SLEEPING -> 1f
            WhaleGirlClip.SUCCESS -> .55f
            WhaleGirlClip.TYPING -> .15f
            else -> 0f
        },
        reducedMotion, 180,
    )
    val puff by whaleShapeState(
        if (clip == WhaleGirlClip.CHEWING) 1f else 0f,
        reducedMotion, 220,
    )
    val sleepy by whaleShapeState(
        if (clip == WhaleGirlClip.SLEEPING) 1f else 0f,
        reducedMotion, 180,
    )
    val palette = if (dark) WhaleLinePalette.Night else WhaleLinePalette.Day
    val watchingInput = clip == WhaleGirlClip.FOCUSED || clip == WhaleGirlClip.TYPING
    val gazeX by whaleShapeState(attentionTarget?.x?.coerceIn(-1f, 1f)?.times(7f) ?: 0f, reducedMotion, 160)
    val gazeY by whaleShapeState(if (watchingInput) 9f else
        attentionTarget?.y?.coerceIn(-1f, 1f)?.times(5f) ?: 0f, reducedMotion, 160)
    Canvas(modifier) {
        val unit = minOf(size.width / 672f, size.height / 650f)
        val time = if (reducedMotion) 0.0 else previewSeconds?.toDouble() ?: seconds
        withTransform({
            translate((size.width - unit * 672) / 2, (size.height - unit * 650) / 2)
            scale(unit, unit, Offset.Zero)
        }) {
            val acting = whaleActing(clip, time)
            withTransform({
                translate(0f, -acting.lift)
                rotate(acting.tilt + gazeX * .4f, Offset(330f, 440f))
                scale(1f + acting.breath * .006f, 1f + acting.breath * .009f, Offset(330f, 560f))
            }) {
                val tracking = if (clip == WhaleGirlClip.TYPING)
                    kotlin.math.sin(time * kotlin.math.PI).toFloat() * 5f else 0f
                drawApprovedWhaleHead(clip, palette, closed, puff, sleepy, time, unit,
                    Offset(gazeX + tracking, gazeY))
            }
        }
    }
}

/** Shape interpolation pauses with the same lifecycle as the expression clock. */
@Composable
private fun whaleShapeState(target: Float, reduced: Boolean, duration: Int): State<Float> {
    val shape = remember { Animatable(target) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(target, reduced, lifecycle) {
        if (reduced) shape.snapTo(target)
        else lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            shape.animateTo(target, tween(duration))
        }
    }
    return shape.asState()
}

@Preview(name = "Whale line art — day", widthDp = 336, heightDp = 336)
@Composable
private fun WhaleLineDayPreview() = WhaleLineDesignSheet(dark = false)

@Preview(name = "Whale line art — night", widthDp = 336, heightDp = 336)
@Composable
private fun WhaleLineNightPreview() = WhaleLineDesignSheet(dark = true)

@Composable
internal fun WhaleLineDesignSheet(dark: Boolean) {
    val palette = if (dark) WhaleLinePalette.Night else WhaleLinePalette.Day
    Column(Modifier.background(palette.paper)) {
        listOf(
            listOf(WhaleGirlClip.IDLE, WhaleGirlClip.PETTING),
            listOf(WhaleGirlClip.CHEWING, WhaleGirlClip.THINKING),
        ).forEach { row ->
            Row {
                row.forEach { clip ->
                    WhaleGirlLineArtPortrait(clip, Modifier.size(168.dp), dark, reducedMotion = true)
                }
            }
        }
    }
}
