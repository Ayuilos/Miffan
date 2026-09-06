package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WhaleGirlPlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val backgrounds = listOf(0xFFFFF2DA.toInt(), 0xFF172D33.toInt())

    private class PlaybackLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun mascotPettingWaitsForPlaybackAndSurvivesAPauseBeforeAndDuringTheClip() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    WhaleGirlMascot(
                        state = MiffanMascotState.Idle,
                        interactive = true,
                        dayPhase = MiffanDayPhase.Noon,
                        modifier = Modifier.size(168.dp).background(Color(backgrounds.first())).testTag("mascot"),
                    )
                }
            }
        }
        advanceAndDraw(100)
        compose.onNodeWithTag("mascot").performClick()
        advanceAndDraw(100)
        val pettingDescription = "蓝色大肥鱼，正在被摸摸"
        compose.onNodeWithContentDescription(pettingDescription).assertExists()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onAllNodes(SemanticsMatcher.expectValue(WhaleAtlasLoadedKey, true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The old click-time deadline expired here, before a single playback frame could run.
        advanceAndDraw(2_500)
        compose.onNodeWithContentDescription(pettingDescription).assertExists()
        resume(owner)
        val first = capture("mascot")
        advanceAndDraw(700)
        compose.onNodeWithContentDescription(pettingDescription).assertExists()
        assertTrue("The real Mascot must play petting frames after resuming", !first.sameAs(capture("mascot")))
        pause(owner)
        val paused = capture("mascot")
        advanceAndDraw(3_000)
        compose.onNodeWithContentDescription(pettingDescription).assertExists()
        assertTrue("Petting must keep its current frame while paused", paused.sameAs(capture("mascot")))
        resume(owner)
        advanceAndDraw(1_000)
        compose.onNodeWithContentDescription(pettingDescription).assertDoesNotExist()
        compose.onNodeWithContentDescription("蓝色大肥鱼").assertExists()
        pause(owner)
    }

    @Test
    fun packagedMetadataMatchesAllEightTransparentRuntimeClips() {
        val metadata = context.assets.open("whale_motion/metadata.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(320, metadata.getInt("frameWidth"))
        assertEquals(WHALE_FRAME_SIZE, metadata.getInt("frameWidth"))
        assertEquals(WHALE_FRAME_SIZE, metadata.getInt("frameHeight"))
        assertEquals(10, metadata.getInt("columns"))
        assertEquals(WHALE_ATLAS_COLUMNS, metadata.getInt("columns"))
        assertEquals(30, metadata.getInt("fps"))
        val clips = metadata.getJSONObject("clips")
        assertEquals("One shared transparent set replaces the separate light/dark atlases", 8, clips.length())
        assertEquals(8, WhaleGirlClip.entries.size)
        WhaleGirlClip.entries.forEach { clip ->
            val record = clips.getJSONObject(clip.assetStem)
            assertEquals(30, clip.framesPerSecond)
            assertEquals(if (clip.looping) 120 else 45, clip.frameCount)
            assertEquals(if (clip.looping) 4_000L else 1_500L, clip.durationMillis)
            assertEquals(clip.assetStem, clip.frameCount, record.getInt("frameCount"))
            assertEquals(clip.assetStem, clip.durationMillis, record.getLong("durationMs"))
            assertEquals(clip.assetStem, clip.looping, record.getBoolean("loop"))
            assertEquals(clip.assetStem, clip.framesPerSecond, record.getInt("fps"))
            assertEquals(clip.assetPath().substringAfterLast('/'), record.getString("asset"))
            assertEquals((clip.frameCount + WHALE_ATLAS_COLUMNS - 1) / WHALE_ATLAS_COLUMNS, record.getInt("rows"))
            assertNativeSourceWindow(record, clip)
            val bitmap = decodeAtlas(clip)
            try {
                assertTrue("${clip.assetStem} must retain alpha", bitmap.hasAlpha())
                assertEquals(3_200, bitmap.width)
                assertEquals(if (clip.looping) 3_840 else 1_600, bitmap.height)
                assertTrue("One decoded $clip atlas must fit the cache budget",
                    bitmap.allocationByteCount <= WHALE_ATLAS_CACHE_BYTES)
                assertAdjacentFramesAreAuthoredMotion(bitmap, clip)
                repeat(clip.frameCount) { frame ->
                    val left = frame % WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE
                    val top = frame / WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE
                    var transparent = 0
                    var opaque = 0
                    for (y in 4 until WHALE_FRAME_SIZE step 10) {
                        for (x in 4 until WHALE_FRAME_SIZE step 10) {
                            val alpha = AndroidColor.alpha(bitmap.getPixel(left + x, top + y))
                            if (alpha == 0) transparent++
                            if (alpha >= 250) opaque++
                        }
                    }
                    assertTrue("$clip frame $frame must have real transparent background", transparent > 50)
                    assertTrue("$clip frame $frame must preserve the opaque character", opaque > 50)
                    listOf(0 to 0, WHALE_FRAME_SIZE - 1 to 0,
                        0 to WHALE_FRAME_SIZE - 1, WHALE_FRAME_SIZE - 1 to WHALE_FRAME_SIZE - 1).forEach { (x, y) ->
                        assertEquals("$clip frame $frame corner must be transparent", 0,
                            AndroidColor.alpha(bitmap.getPixel(left + x, top + y)))
                    }
                }
            } finally { bitmap.recycle() }
            preload(clip)
        }
    }

    @Test
    fun whiteHeadbandStaysOpaqueAcrossPreviouslyFlickeringFrames() {
        data class HeadbandPatch(val clip: WhaleGirlClip, val frames: IntRange,
            val x: Int, val y: Int)
        // These 3 × 2 patches are inside the curved white band, away from its black
        // outlines. In the original 320px source frames every channel is >= 243.
        // Eating 54/56/57/65 and idle 48 are intact controls; the interleaved frames
        // had alpha 0–5 after the matte incorrectly treated the band as a hair hole.
        // Coordinates were checked against headband-investigation/{eating,idle}-headband.png.
        val patches = listOf(
            HeadbandPatch(WhaleGirlClip.EATING, 54..65, 151, 60),
            HeadbandPatch(WhaleGirlClip.IDLE, 48..50, 162, 66),
            HeadbandPatch(WhaleGirlClip.IDLE, 75..76, 162, 66),
        )
        patches.groupBy { it.clip }.forEach { (clip, clipPatches) ->
            val atlas = decodeAtlas(clip)
            try {
                clipPatches.forEach { patch ->
                    patch.frames.forEach { frame ->
                        val left = frame % WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE
                        val top = frame / WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE
                        for (y in patch.y until patch.y + 2) {
                            for (x in patch.x until patch.x + 3) {
                                val pixel = atlas.getPixel(left + x, top + y)
                                val label = "$clip frame $frame headband ($x, $y)"
                                assertTrue("$label must remain opaque, alpha=${AndroidColor.alpha(pixel)}",
                                    AndroidColor.alpha(pixel) >= 245)
                                assertTrue("$label must preserve the white artwork",
                                    minOf(AndroidColor.red(pixel), AndroidColor.green(pixel),
                                        AndroidColor.blue(pixel)) >= 230)
                            }
                        }
                    }
                }
            } finally {
                atlas.recycle()
            }
        }
    }

    @Test
    fun transparentAtlasesAndPostersRevealBothBackgroundsWithoutRectanglesOrGhostHeads() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        var background by mutableIntStateOf(backgrounds.first())
        compose.setContent { PortraitAndPoster(clip, owner, background) }
        WhaleGirlClip.entries.forEach { next ->
            preload(next)
            compose.runOnIdle { clip = next }
            awaitAtlasDrawn(next)
            val atlas = decodeAtlas(next)
            val poster = requireNotNull(BitmapFactory.decodeResource(context.resources, whaleGirlPoster(next),
                BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
            try {
                assertTrue("$next poster must preserve real transparency", poster.hasAlpha())
                backgrounds.forEachIndexed { index, color ->
                    compose.runOnIdle { background = color }
                    advanceAndDraw(200)
                    val playing = capture("playing")
                    val still = capture("poster")
                    assertTransparentPixelsRevealBackground(playing, atlas, WHALE_FRAME_SIZE, color, "$next atlas")
                    assertTransparentPixelsRevealBackground(still, poster, poster.width, color, "$next poster")
                    savePair("whale-girl-alpha-${next.assetStem}-$index.png", playing, still)
                }
            } finally {
                atlas.recycle()
                poster.recycle()
            }
        }
    }

    @Test
    fun allLoopingStatesKeepChangingAfterTheirFirstCycle() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        compose.setContent { PortraitAndPoster(clip, owner) }
        WhaleGirlClip.entries.filter { it.looping }.forEach { next ->
            pause(owner)
            preload(next)
            compose.runOnIdle { clip = next }
            awaitAtlasDrawn(next)
            resume(owner)
            val first = capture("playing")
            advanceAndDraw(next.durationMillis / 2)
            val during = capture("playing")
            assertTrue("$next must change its own portrait pixels", !first.sameAs(during))
            advanceAndDraw(next.durationMillis)
            val secondCycle = capture("playing")
            advanceAndDraw(next.durationMillis / 4)
            assertTrue("$next must continue beyond its first cycle", !secondCycle.sameAs(capture("playing")))
            savePair("whale-girl-playback-${next.assetStem}.png", first, during)
        }
        pause(owner)
    }

    @Test
    fun everyOneShotAnimatesThenHoldsItsFinalFrameAndReplaysOnRequest() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        var clip by mutableStateOf(WhaleGirlClip.PETTING)
        var replayId by mutableIntStateOf(0)
        compose.setContent { PortraitAndPoster(clip, owner, replayId = replayId) }
        WhaleGirlClip.entries.filter { !it.looping }.forEach { next ->
            pause(owner)
            preload(next)
            compose.runOnIdle { clip = next; replayId = 0 }
            awaitAtlasDrawn(next)
            resume(owner)
            val first = capture("playing")
            advanceAndDraw(next.durationMillis / 2)
            val during = capture("playing")
            assertTrue("$next must animate before completing", !first.sameAs(during))
            advanceAndDraw(next.durationMillis + 300)
            val last = capture("playing")
            advanceAndDraw(next.durationMillis + 1_000)
            assertTrue("$next must hold its final frame without looping", last.sameAs(capture("playing")))
            pause(owner)
            compose.runOnIdle { replayId++ }
            advanceAndDraw(300)
            assertTrue("$next replay must reset to its first frame", first.sameAs(capture("playing")))
            savePair("whale-girl-playback-${next.assetStem}.png", first, during)
        }
        pause(owner)
    }

    @Test
    fun historicalAndReducedMotionPortraitsRemainStaticForEveryState() {
        compose.mainClock.autoAdvance = false
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        compose.setContent {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhaleGirlAnimatedPortrait(clip, playing = false, posterResourceId = whaleGirlPoster(clip),
                    modifier = Modifier.size(144.dp).testTag("historical"))
                WhaleGirlAnimatedPortrait(clip, playing = true, reducedMotion = true,
                    posterResourceId = whaleGirlPoster(clip), modifier = Modifier.size(144.dp).testTag("reduced"))
            }
        }
        WhaleGirlClip.entries.forEach { next ->
            compose.runOnIdle { clip = next }
            advanceAndDraw(200)
            val before = listOf("historical", "reduced").associateWith(::capture)
            advanceAndDraw(10_000)
            before.forEach { (tag, bitmap) ->
                assertTrue("$next $tag must remain pixel-identical", bitmap.sameAs(capture(tag)))
                assertEquals(false, compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(WhaleAtlasLoadedKey))
            }
        }
    }

    @Test
    fun lifecyclePauseFreezesTheFrameAndResumeDoesNotCatchUp() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        val clip = WhaleGirlClip.THINKING
        preload(clip)
        compose.setContent { PortraitAndPoster(clip, owner) }
        awaitAtlasDrawn(clip)
        resume(owner)
        advanceAndDraw(500)
        pause(owner)
        val paused = capture("playing")
        advanceAndDraw(10_000)
        assertTrue("A background screen must retain the same frame", paused.sameAs(capture("playing")))
        resume(owner)
        assertTrue("Resume must use a fresh origin without catching up", paused.sameAs(capture("playing")))
        advanceAndDraw(500)
        assertTrue("The resumed portrait must continue animating", !paused.sameAs(capture("playing")))
        pause(owner)
    }

    @Composable
    private fun PortraitAndPoster(clip: WhaleGirlClip, owner: PlaybackLifecycleOwner,
        background: Int = backgrounds.first(), replayId: Int = 0) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhaleGirlAnimatedPortrait(clip, playing = true, replayId = replayId,
                    posterResourceId = whaleGirlPoster(clip),
                    modifier = Modifier.size(144.dp).background(Color(background)).testTag("playing"))
                WhaleGirlAnimatedPortrait(clip, playing = false, posterResourceId = whaleGirlPoster(clip),
                    modifier = Modifier.size(144.dp).background(Color(background)).testTag("poster"))
            }
        }
    }

    private fun assertTransparentPixelsRevealBackground(rendered: Bitmap, source: Bitmap,
        frameSize: Int, background: Int, label: String) {
        var samples = 0
        var mismatches = 0
        // The paused atlas displays frame zero. Test interior transparent regions too:
        // a permanent poster under the animated head would show through those pixels.
        for (y in 4 until frameSize - 4 step 8) {
            for (x in 4 until frameSize - 4 step 8) {
                val clear = (-3..3).all { dy -> (-3..3).all { dx ->
                    AndroidColor.alpha(source.getPixel(x + dx, y + dy)) == 0
                } }
                if (!clear) continue
                samples++
                val actual = rendered.getPixel(x * rendered.width / frameSize, y * rendered.height / frameSize)
                if (AndroidColor.alpha(actual) != 255 ||
                    abs(AndroidColor.red(actual) - AndroidColor.red(background)) > 2 ||
                    abs(AndroidColor.green(actual) - AndroidColor.green(background)) > 2 ||
                    abs(AndroidColor.blue(actual) - AndroidColor.blue(background)) > 2) mismatches++
            }
        }
        assertTrue("$label must contain a substantial transparent background", samples > 30)
        assertEquals("$label has opaque background or a second head showing through ($samples samples)", 0, mismatches)
    }

    private fun assertNativeSourceWindow(record: JSONObject, clip: WhaleGirlClip) {
        val sourceFps = record.getInt("sourceFps")
        assertEquals("The original videos are 24 fps, not native 30 fps", 24, sourceFps)
        assertEquals("native-frame-selection", record.getString("temporalMethod"))
        val indices = record.getJSONArray("sourceFrameIndices")
        assertEquals("Each output frame must identify its original source frame", clip.frameCount, indices.length())
        val sourceCount = record.getInt("sourceFrameCount")
        val start = record.getDouble("sourceStartSeconds")
        val end = record.getDouble("sourceEndSeconds")
        assertEquals(0.0, start, 0.0001)
        assertEquals(if (clip.looping) 5.125 else 3.0, end, 1.0 / sourceFps)
        assertTrue("$clip must use a longer authored window to supply distinct 30 fps playback frames",
            end - start > clip.durationMillis / 1_000.0)
        assertTrue("$clip source window must contain enough native frames without duplication",
            (end - start) * sourceFps + 1 >= clip.frameCount)
        var previous = -1
        repeat(indices.length()) { frame ->
            val index = indices.getInt(frame)
            assertTrue("$clip frame $frame must advance to a fresh native frame", index > previous)
            assertTrue("$clip frame $frame must exist in the original video", index in 0 until sourceCount)
            val seconds = index.toDouble() / sourceFps
            assertTrue("$clip frame $frame must remain in its authored window",
                seconds >= start - 0.0001 && seconds <= end + 0.0001)
            previous = index
        }
    }

    private fun assertAdjacentFramesAreAuthoredMotion(atlas: Bitmap, clip: WhaleGirlClip) {
        var previous = IntArray(WHALE_FRAME_SIZE * WHALE_FRAME_SIZE)
        var current = IntArray(previous.size)
        var movingPairs = 0
        repeat(clip.frameCount) { frame ->
            atlas.getPixels(current, 0, WHALE_FRAME_SIZE,
                frame % WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE,
                frame / WHALE_ATLAS_COLUMNS * WHALE_FRAME_SIZE,
                WHALE_FRAME_SIZE, WHALE_FRAME_SIZE)
            if (frame > 0) {
                var changed = 0
                // Sample the character, ignoring invisible RGB and tiny compression noise.
                for (y in 0 until WHALE_FRAME_SIZE step 4) {
                    for (x in 0 until WHALE_FRAME_SIZE step 4) {
                        val index = y * WHALE_FRAME_SIZE + x
                        val before = previous[index]
                        val after = current[index]
                        if (minOf(AndroidColor.alpha(before), AndroidColor.alpha(after)) < 128) continue
                        if (maxOf(abs(AndroidColor.red(before) - AndroidColor.red(after)),
                                abs(AndroidColor.green(before) - AndroidColor.green(after)),
                                abs(AndroidColor.blue(before) - AndroidColor.blue(after))) > 4) changed++
                    }
                }
                if (changed >= 8) movingPairs++
            }
            val swap = previous
            previous = current
            current = swap
        }
        // Native source indices separately forbid repeated selections. Pixel sampling also
        // rejects a stale/static atlas while allowing naturally held expressions in the video.
        assertTrue("$clip must visibly change in most adjacent frames ($movingPairs/${clip.frameCount - 1})",
            movingPairs.toDouble() / (clip.frameCount - 1) > 0.5)
    }

    private fun decodeAtlas(clip: WhaleGirlClip): Bitmap = context.assets.open(clip.assetPath()).use {
        requireNotNull(BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    }

    private fun newPausedOwner(): PlaybackLifecycleOwner = compose.runOnUiThread {
        PlaybackLifecycleOwner().apply { registry.currentState = Lifecycle.State.STARTED }
    }
    private fun pause(owner: PlaybackLifecycleOwner) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
    }
    private fun resume(owner: PlaybackLifecycleOwner) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun preload(clip: WhaleGirlClip) {
        assertTrue("The packaged $clip atlas must decode", runBlocking { preloadWhaleGirlAtlas(context.assets, clip) })
    }
    private fun awaitAtlasDrawn(clip: WhaleGirlClip) {
        advanceAndDraw(300)
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            whaleGirlAtlasIsCached(clip) &&
                compose.onNodeWithTag("playing").fetchSemanticsNode().config.getOrNull(WhaleAtlasLoadedKey) == true
        }
        advanceAndDraw(200)
    }
    private fun advanceAndDraw(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        .copy(Bitmap.Config.ARGB_8888, false)
    private fun savePair(name: String, before: Bitmap, after: Bitmap) {
        val matrix = Bitmap.createBitmap(before.width + after.width, maxOf(before.height, after.height), Bitmap.Config.ARGB_8888)
        Canvas(matrix).apply {
            drawBitmap(before, 0f, 0f, null)
            drawBitmap(after, before.width.toFloat(), 0f, null)
        }
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { matrix.compress(Bitmap.CompressFormat.PNG, 100, it) }
        matrix.recycle()
    }
}
