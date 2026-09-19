package me.ayuilos.miffan.ui.components.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.ayuilos.miffan.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceLocalizationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun introductionUpdatesInAllSixLanguagesAndKeepsActionsReachable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val language = mutableStateOf("en")
        compose.setContent {
            val config = remember(language.value) {
                Configuration(base.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language.value))
                }
            }
            val context = remember(language.value) { base.createConfigurationContext(config) }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalResources provides context.resources,
            ) {
                MaterialTheme { WorkspaceIntroduction(onDismiss = {}, onOpenWorkspaces = {}) }
            }
        }
        for (tag in listOf("en", "zh-CN", "zh-TW", "ja", "ko-KR", "ru")) {
            compose.runOnIdle { language.value = tag }
            val resources = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }).resources
            compose.onNodeWithText(resources.getString(R.string.workspace_intro_title))
                .performScrollTo().assertIsDisplayed()
            if (tag in listOf("en", "ja", "ru")) {
                val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                File(base.getExternalFilesDir(null), "workspace-intro-$tag.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithText(resources.getString(R.string.workspace_open_workspaces))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(resources.getString(R.string.workspace_later))
                .performScrollTo().assertIsDisplayed()
        }
    }
}
