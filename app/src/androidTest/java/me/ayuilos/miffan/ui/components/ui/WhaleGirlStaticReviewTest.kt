package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Captures for human visual approval, not an automated assertion of artistic fidelity. */
class WhaleGirlStaticReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureStaticFaceAgainstApprovedReference() {
        val dark = mutableStateOf(false)
        compose.setContent {
            WhaleGirlStaticReview(Modifier.size(336.dp, 325.dp).testTag("face"), dark.value)
        }
        val day = compose.onNodeWithTag("face").captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
        save("native-static-day.png", day)
        compose.runOnIdle { dark.value = true }
        val night = compose.onNodeWithTag("face").captureToImage().asAndroidBitmap()
            .copy(Bitmap.Config.ARGB_8888, false)
        save("native-static-night.png", night)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reference = instrumentation.context.assets.open("whale-review/reference-day.png")
            .use { BitmapFactory.decodeStream(it) }
        // Same coordinate frame and scale. Left is the unretouched reference crop,
        // right is the captured Android Canvas. Only labels and layout are added.
        val result = Bitmap.createBitmap(day.width * 2, day.height + 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 52, 107); textSize = 28f }
        canvas.drawText("APPROVED REFERENCE", 24f, 42f, label)
        canvas.drawText("ANDROID NATIVE / STATIC", day.width + 24f, 42f, label)
        canvas.drawBitmap(reference, Rect(0, 0, 672, 650), Rect(0, 64, day.width, day.height + 64), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawBitmap(day, day.width.toFloat(), 64f, null)
        save("reference-vs-native.png", result)
    }

    private fun save(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "whale-static-review").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
