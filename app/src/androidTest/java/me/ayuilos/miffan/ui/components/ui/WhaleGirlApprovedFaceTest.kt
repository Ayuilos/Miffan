package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the approved drawing's integration; artistic fidelity still needs human review. */
@RunWith(AndroidJUnit4::class)
class WhaleGirlApprovedFaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun productionIdlePreservesTheApprovedStaticDrawingAtAvatarAndPortraitSizes() {
        compose.mainClock.autoAdvance = false
        var review by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var dimension by mutableStateOf(28)
        compose.setContent {
            val paper = if (dark) WhaleLinePalette.Night.paper else WhaleLinePalette.Day.paper
            // Capture the opaque parent at the same screen position, so transparent outer
            // portrait pixels and antialiased contour edges have the same compositing base.
            Box(Modifier.size(dimension.dp).background(paper).testTag("approved-comparison")) {
                if (review) {
                    WhaleGirlStaticReview(Modifier.size(dimension.dp), dark)
                } else {
                    WhaleGirlLineArtPortrait(
                        clip = WhaleGirlClip.IDLE,
                        modifier = Modifier.size(dimension.dp),
                        dark = dark,
                        reducedMotion = true,
                    )
                }
            }
        }
        for (palette in listOf(false, true)) {
            for (size in listOf(28, 40, 168, 280)) {
                compose.runOnIdle { dark = palette; dimension = size; review = true }
                settle()
                val approved = capture("approved-comparison")
                compose.runOnIdle { review = false }
                settle()
                assertTrue("Production Idle must preserve the approved face at $size dp, dark=$palette",
                    approved.sameAs(capture("approved-comparison")))
            }
        }
    }

    @Test
    fun everyExpressionLeavesAvatarCornersTransparentOnBothBackgrounds() {
        compose.mainClock.autoAdvance = false
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        var dark by mutableStateOf(false)
        var background by mutableStateOf(Color(0xFFFFE9C2))
        compose.setContent {
            Box(Modifier.size(168.dp).background(background).testTag("transparent-head")) {
                WhaleGirlLineArtPortrait(
                    clip = clip,
                    dark = dark,
                    reducedMotion = true,
                    modifier = Modifier.size(168.dp),
                )
            }
        }
        for (palette in listOf(false, true)) {
            for (surface in listOf(Color(0xFFFFE9C2), Color(0xFF354B46))) {
                for (expression in WhaleGirlClip.entries) {
                    compose.runOnIdle { dark = palette; background = surface; clip = expression }
                    settle()
                    val bitmap = capture("transparent-head")
                    // A patch in each corner catches an accidental opaque Canvas rectangle,
                    // including one matching the default theme but obscuring a custom surface.
                    val patch = maxOf(1, bitmap.width / 32)
                    for (xStart in listOf(0, bitmap.width - patch)) {
                        for (yStart in listOf(0, bitmap.height - patch)) {
                            for (x in xStart until xStart + patch) {
                                for (y in yStart until yStart + patch) {
                                    assertEquals("$expression outer corner ($x,$y), dark=$palette",
                                        surface.toArgb(), bitmap.getPixel(x, y))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage()
        .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
}
