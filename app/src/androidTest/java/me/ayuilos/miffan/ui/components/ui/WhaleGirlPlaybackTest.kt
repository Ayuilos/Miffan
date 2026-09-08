package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhaleGirlPlaybackTest {
    @get:Rule
    val compose = createComposeRule()

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
    fun nativePettingCompletesOnceAndReplaysIncludingReducedMotion() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        var replayId by mutableIntStateOf(0)
        val completed = IntArray(2)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Row {
                    listOf(false, true).forEachIndexed { index, reduced ->
                        WhaleGirlLineArtPortrait(
                            clip = WhaleGirlClip.PETTING,
                            playing = true,
                            reducedMotion = reduced,
                            replayId = replayId,
                            onPlaybackFinished = { completed[index]++ },
                            modifier = Modifier.size(128.dp).testTag("one-shot-$index"),
                        )
                    }
                }
            }
        }
        advanceAndDraw(2_000)
        assertEquals("Paused petting must not finish before playback", listOf(0, 0), completed.toList())
        resume(owner)
        advanceAndDraw(600)
        assertEquals(listOf(0, 0), completed.toList())
        pause(owner)
        val paused = listOf("one-shot-0", "one-shot-1").associateWith(::capture)
        advanceAndDraw(3_000)
        assertEquals("Background time must not consume petting", listOf(0, 0), completed.toList())
        paused.forEach { (tag, bitmap) -> assertTrue(bitmap.sameAs(capture(tag))) }
        resume(owner)
        advanceAndDraw(1_200)
        assertEquals("Both normal and reduced petting must finish", listOf(1, 1), completed.toList())
        val ended = listOf("one-shot-0", "one-shot-1").associateWith(::capture)
        advanceAndDraw(3_000)
        assertEquals("Completion callback fires once", listOf(1, 1), completed.toList())
        ended.forEach { (tag, bitmap) -> assertTrue("Finished petting holds still", bitmap.sameAs(capture(tag))) }
        compose.runOnIdle { replayId++ }
        advanceAndDraw(600)
        assertEquals("Replay gets its own duration", listOf(1, 1), completed.toList())
        advanceAndDraw(1_200)
        assertEquals("A replay produces one new completion", listOf(2, 2), completed.toList())
        pause(owner)
    }

    @Test
    fun explicitPlayingFalseDoesNotConsumeReactionOrNotifyCompletion() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        var playing by mutableStateOf(false)
        var completed = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WhaleGirlLineArtPortrait(
                    clip = WhaleGirlClip.PETTING,
                    playing = playing,
                    onPlaybackFinished = { completed++ },
                    modifier = Modifier.size(128.dp).testTag("explicitly-paused"),
                )
            }
        }
        resume(owner)
        advanceAndDraw(3_000)
        assertEquals("A static reaction with a callback must not complete", 0, completed)
        compose.runOnIdle { playing = true }
        advanceAndDraw(600)
        assertEquals(0, completed)
        compose.runOnIdle { playing = false }
        advanceAndDraw(3_000)
        assertEquals("Explicit pause must preserve the remaining reaction duration", 0, completed)
        compose.runOnIdle { playing = true }
        advanceAndDraw(1_200)
        assertEquals("The resumed reaction finishes exactly once", 1, completed)
        advanceAndDraw(2_000)
        assertEquals(1, completed)
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
            advanceAndDraw(500)
            val before = listOf("historical", "reduced").associateWith(::capture)
            advanceAndDraw(10_000)
            before.forEach { (tag, bitmap) ->
                assertTrue("$next $tag must remain pixel-identical", bitmap.sameAs(capture(tag)))
            }
        }
    }

    @Test
    fun lifecyclePauseFreezesTheFrameAndResumeDoesNotCatchUp() {
        compose.mainClock.autoAdvance = false
        val owner = newPausedOwner()
        val clip = WhaleGirlClip.CHEWING
        compose.setContent { PortraitAndPoster(clip, owner) }
        advanceAndDraw(500)
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
    private fun advanceAndDraw(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        .copy(Bitmap.Config.ARGB_8888, false)
}
