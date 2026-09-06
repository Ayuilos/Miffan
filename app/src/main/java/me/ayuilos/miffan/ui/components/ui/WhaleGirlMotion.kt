package me.ayuilos.miffan.ui.components.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

enum class WhaleGirlClip(
    val assetStem: String,
    val frameCount: Int,
    val durationMillis: Long,
    val framesPerSecond: Int,
    val looping: Boolean,
) {
    IDLE("idle", 120, 4_000, 30, true),
    PETTING("petting", 45, 1_500, 30, false),
    SUCCESS("success", 45, 1_500, 30, false),
    SURPRISE("surprise", 45, 1_500, 30, false),
    EATING("eating", 120, 4_000, 30, true),
    CHEWING("chewing", 120, 4_000, 30, true),
    THINKING("thinking", 120, 4_000, 30, true),
    SLEEPING("sleeping", 120, 4_000, 30, true);

    fun assetPath(): String = "whale_motion/$assetStem.webp"
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

private data class WhalePortrait(val clip: WhaleGirlClip, val replayId: Int, @DrawableRes val posterResourceId: Int)

internal val WhaleAtlasLoadedKey = SemanticsPropertyKey<Boolean>("WhaleAtlasLoaded")

/** Deterministic preloading for visual verification; the regular UI loads only on demand. */
internal suspend fun preloadWhaleGirlAtlas(assets: AssetManager, clip: WhaleGirlClip): Boolean =
    WhaleAtlasCache.load(assets, clip) != null

internal fun whaleGirlAtlasIsCached(clip: WhaleGirlClip): Boolean = WhaleAtlasCache.contains(clip.assetPath())

/**
 * Static/historical portraits never enter the atlas-loading or clock branches. Each layer uses
 * either its poster or its atlas: a still underneath transparent video would produce ghost faces.
 */
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
) {
    val animate = playing && !reducedMotion
    val loaded = remember(clip, replayId, animate) { mutableStateOf(false) }
    Box(modifier.aspectRatio(1f).semantics { this[WhaleAtlasLoadedKey] = loaded.value }) {
        if (animate) {
            val target = WhalePortrait(clip, replayId, posterResourceId)
            Crossfade(
                targetState = target,
                animationSpec = tween(160),
                label = "whale_portrait",
            ) { portrait ->
                WhaleAtlasLayer(
                    portrait = portrait,
                    active = portrait == target,
                    onLoaded = { if (portrait == target) loaded.value = it },
                    onFinished = { if (portrait == target) onPlaybackFinished?.invoke() },
                    onUnavailable = { if (portrait == target) onPlaybackUnavailable?.invoke() },
                )
            }
        } else {
            WhalePoster(posterResourceId)
        }
    }
}

@Composable
private fun WhalePoster(@DrawableRes resourceId: Int) {
    Image(
        painter = painterResource(resourceId),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun WhaleAtlasLayer(
    portrait: WhalePortrait,
    active: Boolean,
    onLoaded: (Boolean) -> Unit,
    onFinished: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val assets = LocalContext.current.applicationContext.assets
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var bitmap by remember(portrait) { mutableStateOf<Bitmap?>(null) }
    val timeline = remember(portrait) { WhaleGirlTimeline(portrait.clip) }
    var frameIndex by remember(timeline) { mutableIntStateOf(0) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    LaunchedEffect(portrait, active) {
        if (active && bitmap == null) {
            bitmap = WhaleAtlasCache.load(assets, portrait.clip)
            if (bitmap == null) currentOnUnavailable()
        }
    }
    SideEffect { onLoaded(bitmap != null) }
    LaunchedEffect(timeline, bitmap, active, lifecycle) {
        if (!active || bitmap == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (timeline.finished) return@repeatOnLifecycle
            var previousFrame = withFrameNanos { it }
            while (currentCoroutineContext().isActive && !timeline.finished) {
                val now = withFrameNanos { it }
                timeline.advance(now - previousFrame)
                previousFrame = now
                // The timeline already selects the authored 30 fps frame. A second sampling
                // clock would delay frame boundaries after resuming partway through a frame.
                // Vsync reads do not invalidate drawing until the selected source frame changes.
                val nextFrame = timeline.frameIndex
                if (frameIndex != nextFrame) frameIndex = nextFrame
            }
            if (timeline.finished) currentOnFinished()
        }
    }
    val atlas = bitmap
    if (atlas == null) {
        WhalePoster(portrait.posterResourceId)
    } else {
        val image = remember(atlas) { atlas.asImageBitmap() }
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image = image,
                srcOffset = IntOffset(frameIndex % WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE,
                    frameIndex / WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE),
                srcSize = IntSize(WHALE_FRAME_SIZE, WHALE_FRAME_SIZE),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

/** Shared by all portraits; evicted bitmaps remain valid for any currently displayed layer. */
private object WhaleAtlasCache {
    private val images = object : LruCache<String, Bitmap>(WHALE_ATLAS_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val decodeMutex = Mutex()

    fun contains(path: String): Boolean = images.get(path) != null

    suspend fun load(assets: AssetManager, clip: WhaleGirlClip): Bitmap? = withContext(Dispatchers.IO) {
        val path = clip.assetPath()
        decodeMutex.withLock {
            images.get(path)?.let { return@withLock it }
            try {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@withLock null
                val rows = (clip.frameCount + WHALE_ATLAS_COLUMNS - 1) / WHALE_ATLAS_COLUMNS
                if (bitmap.width != WHALE_FRAME_SIZE * WHALE_ATLAS_COLUMNS || bitmap.height != WHALE_FRAME_SIZE * rows) {
                    return@withLock null
                }
                images.put(path, bitmap)
                bitmap
            } catch (_: IOException) {
                // The packaged still remains visible if an optional atlas is missing or unreadable.
                null
            }
        }
    }
}

internal const val WHALE_FRAME_SIZE = 320
internal const val WHALE_ATLAS_COLUMNS = 10
internal const val WHALE_ATLAS_CACHE_BYTES = 64 * 1024 * 1024
