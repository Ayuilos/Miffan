package me.ayuilos.miffan.utils

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LauncherIconManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun switchingEveryIconKeepsOneLauncherAndTheShareEntryPoint() {
        val manager = LauncherIconManager(context)
        val packageManager = context.packageManager
        val originalSelection = manager.selectedIcon() ?: LauncherIcon.MIFFAN
        val route = ComponentName(context.packageName, "me.ayuilos.miffan.RouteActivity")
        val originalRouteState = packageManager.getComponentEnabledSetting(route)
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val shareIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(context.packageName)

        try {
            // Switch away from the current choice first, and exercise restoring Miffan too.
            val choices = LauncherIcon.entries.filter { it != originalSelection } + originalSelection
            choices.forEach { icon ->
                manager.select(icon)
                // A new manager must recover the selection from Android without app preferences.
                assertEquals(icon, LauncherIconManager(context).selectedIcon())

                val launchers = packageManager.queryIntentActivities(
                    launcherIntent, PackageManager.GET_META_DATA,
                )
                assertEquals("Exactly one launcher should resolve for $icon", 1, launchers.size)
                val launcher = launchers.single().activityInfo
                assertEquals("me.ayuilos.miffan.launcher.${icon.aliasName}", launcher.name)
                assertEquals("me.ayuilos.miffan.RouteActivity", launcher.targetActivity)
                // ComponentInfo.enabled reflects the manifest default; runtime overrides
                // are queried separately (whale aliases are disabled in the manifest).
                assertEquals(
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    packageManager.getComponentEnabledSetting(ComponentName(context.packageName, launcher.name)),
                )
                assertEquals(R.xml.shortcuts, launcher.metaData.getInt("android.app.shortcuts"))

                assertEquals(originalRouteState, packageManager.getComponentEnabledSetting(route))
                assertTrue(packageManager.getActivityInfo(route, 0).enabled)
                assertNotNull(packageManager.resolveActivity(Intent().setComponent(route), 0))
                val shareTargets = packageManager.queryIntentActivities(
                    shareIntent, PackageManager.MATCH_DEFAULT_ONLY,
                )
                assertTrue(
                    "Sharing text must still resolve RouteActivity after selecting $icon",
                    shareTargets.any { it.activityInfo.name == route.className },
                )

                // Selecting the active option again must be harmless.
                manager.select(icon)
                assertEquals(1, packageManager.queryIntentActivities(launcherIntent, 0).size)
            }
        } finally {
            manager.select(originalSelection)
        }
        assertEquals(originalSelection, manager.selectedIcon())
    }

    @Test
    fun launcherResourcesInflateAndRenderWithOriginalIconMonochromeOnly() {
        LauncherIcon.entries.forEach { icon ->
            val drawable = requireNotNull(ContextCompat.getDrawable(context, icon.preview))
            assertTrue("${icon.aliasName} must be adaptive", drawable is AdaptiveIconDrawable)
            assertTrue(drawable.intrinsicWidth > 0)
            assertEquals(drawable.intrinsicWidth, drawable.intrinsicHeight)
            assertHasVisiblePixels(drawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val monochrome = (drawable as AdaptiveIconDrawable).monochrome
                if (icon == LauncherIcon.MIFFAN) {
                    assertNotNull("Original Miffan keeps its themed icon layer", monochrome)
                    assertHasVisiblePixels(requireNotNull(monochrome), requireTransparency = true)
                } else {
                    assertNull("Full-color whale artwork must not become a solid monochrome square", monochrome)
                }
            }
        }

        listOf(
            R.drawable.ic_whale_girl,
            R.drawable.ic_whale_girl_deep_sea,
        ).forEach { resource ->
            val drawable = requireNotNull(ContextCompat.getDrawable(context, resource))
            assertTrue(drawable.intrinsicWidth > 0)
            assertEquals(drawable.intrinsicWidth, drawable.intrinsicHeight)
            assertHasVisiblePixels(drawable)
        }

        listOf(
            R.drawable.ic_launcher_foreground_whale_girl,
            R.drawable.ic_launcher_foreground_whale_girl_deep_sea,
        ).forEach { resource ->
            val drawable = requireNotNull(ContextCompat.getDrawable(context, resource))
            assertTrue(drawable.intrinsicWidth > 0)
            assertEquals(drawable.intrinsicWidth, drawable.intrinsicHeight)
            assertHasVisiblePixels(drawable, requireTransparency = true)
        }
    }

    @Test
    fun oneTransparentIdlePortraitRevealsEachLauncherBackground() {
        val poster = requireNotNull(BitmapFactory.decodeResource(
            context.resources, R.drawable.whale_girl_idle_poster,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            },
        ))
        try {
            assertEquals("The shared idle portrait must be square", poster.width, poster.height)
            assertTrue("The idle portrait must carry real alpha", poster.hasAlpha())
            val edges = listOf(
                0 to poster.height / 2, poster.width - 1 to poster.height / 2,
                poster.width / 2 to 0, poster.width / 2 to poster.height - 1,
                0 to 0, poster.width - 1 to 0,
                0 to poster.height - 1, poster.width - 1 to poster.height - 1,
            )
            edges.forEach { (x, y) ->
                assertEquals("The idle portrait edge ($x, $y) must be transparent", 0,
                    Color.alpha(poster.getPixel(x, y)))
            }
        } finally { poster.recycle() }

        data class Artwork(val icon: Int, val background: Int, val name: String)
        val artworks = listOf(
            Artwork(R.mipmap.ic_launcher_whale_girl, R.color.whale_girl_icon_background,
                "whale-girl-launcher-light.png"),
            Artwork(R.mipmap.ic_launcher_whale_girl_deep_sea, R.color.whale_girl_deep_sea_icon_background,
                "whale-girl-launcher-deep-sea.png"),
        )
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        var firstForeground: Bitmap? = null
        try {
            artworks.forEach { artwork ->
                val drawable = requireNotNull(ContextCompat.getDrawable(context, artwork.icon)) as AdaptiveIconDrawable
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(Canvas(bitmap))
                    File(directory, artwork.name).outputStream().use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    val background = ContextCompat.getColor(context, artwork.background)
                    // Points on the cardinal axes lie inside the adaptive mask while remaining
                    // outside the inset foreground. They must show each icon's actual background.
                    listOf(16 to 256, 495 to 256, 256 to 16, 256 to 495).forEach { (x, y) ->
                        val actual = bitmap.getPixel(x, y)
                        assertEquals("${artwork.name} background should be opaque", 255, Color.alpha(actual))
                        assertTrue("${artwork.name} must reveal its own background at ($x, $y)",
                            abs(Color.red(actual) - Color.red(background)) <= 2 &&
                                abs(Color.green(actual) - Color.green(background)) <= 2 &&
                                abs(Color.blue(actual) - Color.blue(background)) <= 2)
                    }
                } finally { bitmap.recycle() }
                val foreground = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                drawable.foreground.setBounds(0, 0, foreground.width, foreground.height)
                drawable.foreground.draw(Canvas(foreground))
                if (firstForeground == null) firstForeground = foreground else {
                    try {
                        assertTrue("Light and deep-sea icons must share the same transparent idle head",
                            requireNotNull(firstForeground).sameAs(foreground))
                    } finally { foreground.recycle() }
                }
            }
        } finally { firstForeground?.recycle() }
    }

    private fun assertHasVisiblePixels(drawable: Drawable, requireTransparency: Boolean = false) {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Drawable rendered empty", pixels.any { Color.alpha(it) > 0 })
            if (requireTransparency) {
                assertTrue("Foreground must retain transparent margins", pixels.any { Color.alpha(it) == 0 })
            }
        } finally {
            bitmap.recycle()
        }
    }
}
