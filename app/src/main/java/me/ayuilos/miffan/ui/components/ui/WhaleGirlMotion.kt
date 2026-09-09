package me.ayuilos.miffan.ui.components.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance

enum class WhaleGirlClip(
    val frameCount: Int,
    val durationMillis: Long,
    val framesPerSecond: Int,
    val looping: Boolean,
) {
    IDLE(120, 4_000, 30, true),
    FOCUSED(120, 4_000, 30, true),
    TYPING(120, 4_000, 30, true),
    SUBMITTED(45, 1_500, 30, false),
    PETTING(45, 1_500, 30, false),
    SUCCESS(45, 1_500, 30, false),
    SURPRISE(45, 1_500, 30, false),
    EATING(120, 4_000, 30, true),
    CHEWING(120, 4_000, 30, true),
    THINKING(120, 4_000, 30, true),
    SLEEPING(120, 4_000, 30, true);
}

/** Playback time contains active foreground time only. A resume supplies a fresh clock origin. */
internal class WhaleGirlTimeline(
    val clip: WhaleGirlClip,
) {
    val durationNanos = clip.durationMillis * 1_000_000L
    var elapsedNanos: Long = 0L
        private set
    val finished: Boolean get() = !clip.looping && elapsedNanos >= durationNanos
    val frameIndex: Int
        get() = if (finished) clip.frameCount - 1
        else ((elapsedNanos * clip.frameCount) / durationNanos).toInt().coerceIn(0, clip.frameCount - 1)

    fun advance(deltaNanos: Long) {
        if (deltaNanos <= 0L || finished) return
        elapsedNanos = if (clip.looping) {
            // Reduce the delta first so a long-running loop cannot overflow the counter.
            (elapsedNanos + deltaNanos % durationNanos) % durationNanos
        } else {
            elapsedNanos + deltaNanos.coerceAtMost(durationNanos - elapsedNanos)
        }
    }
}

/** Compatibility entry point shared by avatars, introduction and settings previews.
 * Legacy poster arguments remain source-compatible; rendering never decodes those resources.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun WhaleGirlAnimatedPortrait(
    clip: WhaleGirlClip,
    playing: Boolean,
    @DrawableRes posterResourceId: Int,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    replayId: Int = 0,
    onPlaybackFinished: (() -> Unit)? = null,
    onPlaybackUnavailable: (() -> Unit)? = null,
    attentionTarget: androidx.compose.ui.geometry.Offset? = null,
) {
    WhaleGirlLineArtPortrait(
        clip = clip,
        modifier = modifier.aspectRatio(1f),
        dark = MaterialTheme.colorScheme.background.luminance() < .5f,
        playing = playing,
        reducedMotion = reducedMotion,
        replayId = replayId,
        onPlaybackFinished = onPlaybackFinished,
        attentionTarget = attentionTarget,
    )
}
