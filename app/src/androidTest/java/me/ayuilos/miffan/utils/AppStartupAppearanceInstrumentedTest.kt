package me.ayuilos.miffan.utils

import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AppStartupAppearanceInstrumentedTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPersistedStartupStyleLoadsTheCorrectIconAndDayNightBackground() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val configured = context.createConfigurationContext(configuration)
            AppStartupAppearance.entries.forEach { appearance ->
                val themed = ContextThemeWrapper(configured, appearance.themeRes)
                val attributes = themed.obtainStyledAttributes(intArrayOf(
                    android.R.attr.windowSplashScreenAnimatedIcon,
                    android.R.attr.windowSplashScreenBackground,
                ))
                try {
                    val icon = attributes.getResourceId(0, 0)
                    assertEquals(if (appearance.whale) R.drawable.ic_startup_whale else R.drawable.ic_splash_icon_miffan, icon)
                    assertNotNull(ContextCompat.getDrawable(themed, icon))
                    assertEquals(ContextCompat.getColor(themed, appearance.backgroundRes), attributes.getColor(1, 0))
                    assertEquals(if (appearance.whale) R.drawable.whale_girl_idle_poster
                        else R.drawable.ic_launcher_foreground_vector_miffan, appearance.portraitRes)
                } finally { attributes.recycle() }
            }
            val expectedWhaleBackground = if (night == Configuration.UI_MODE_NIGHT_YES)
                R.color.whale_girl_deep_sea_icon_background else R.color.whale_girl_icon_background
            assertEquals(ContextCompat.getColor(configured, expectedWhaleBackground),
                ContextCompat.getColor(configured, AppStartupAppearance.WHALE_SYSTEM.backgroundRes))
        }
    }

    @Test
    fun whaleThemeStartupSurvivesActivityRecreationWithoutChangingTheLauncher() {
        val settings = runBlocking { GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first() }
        val manager = LauncherIconManager(context)
        val originalIcon = manager.selectedIcon() ?: LauncherIcon.MIFFAN
        var selected: AppStartupAppearance? = null
        try {
            activityRule.scenario.onActivity { activity ->
                AppStartupAppearanceController.syncCached(activity)
                manager.select(LauncherIcon.MIFFAN)
                selected = AppStartupAppearanceController.syncSettings(activity, WHALE_THEME_ID, false)
                assertTrue(requireNotNull(selected).whale)
                assertEquals(selected, AppStartupAppearanceController.syncSettings(activity, WHALE_THEME_ID, false))
                assertEquals(LauncherIcon.MIFFAN, manager.selectedIcon())
            }
            activityRule.scenario.recreate()
            activityRule.scenario.onActivity { activity ->
                assertEquals(selected, AppStartupAppearanceController.current(activity))
                assertEquals(selected, AppStartupAppearanceController.syncCached(activity))
                assertFalse(AppStartupAppearanceController.syncSettings(activity, "ocean", false).whale)
                manager.select(LauncherIcon.WHALE_GIRL)
                val whaleStartup = AppStartupAppearanceController.current(activity)
                assertTrue(whaleStartup.whale)
                manager.select(LauncherIcon.WHALE_GIRL_DEEP_SEA)
                assertEquals(LauncherIcon.WHALE_GIRL, manager.selectedIcon())
                assertEquals(whaleStartup, AppStartupAppearanceController.current(activity))
            }
        } finally {
            activityRule.scenario.onActivity { activity ->
                AppStartupAppearanceController.syncSettings(activity, settings.themeId, settings.dynamicColor)
                manager.select(originalIcon)
                AppStartupAppearanceController.syncCached(activity)
            }
        }
    }
}
