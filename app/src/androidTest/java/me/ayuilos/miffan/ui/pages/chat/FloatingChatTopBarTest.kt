package me.ayuilos.miffan.ui.pages.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.ui.hooks.readBooleanPreference
import me.ayuilos.miffan.ui.hooks.readStringPreference
import me.ayuilos.miffan.ui.hooks.writeBooleanPreference
import me.ayuilos.miffan.ui.hooks.writeStringPreference
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.CustomTheme
import me.ayuilos.miffan.ui.theme.presets.MinimalThemePreset
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FloatingChatTopBarTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun floatingCapsulesKeepTheirBackdropAndThemeContrast() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settingsStore = koin.get<SettingsStore>()
        val repository = koin.get<ConversationRepository>()
        val savedSettings = settingsStore.settingsFlowRaw.first()
        val savedLastConversation = context.readStringPreference("lastConversationId")
        val savedCreateNew = context.readBooleanPreference("create_new_conversation_on_start", true)
        val savedColorMode = context.readStringPreference("colorMode")
        val savedAmoled = context.readBooleanPreference("amoledDark")
        val backdrop = File(context.cacheDir, "floating-topbar-test-background.png")
        createBackdrop(backdrop)
        val assistant = Assistant(name = "界面测试", background = backdrop.toURI().toString())
        val conversation = Conversation(
            assistantId = assistant.id,
            title = "悬浮胶囊",
            messageNodes = (1..32).map { index ->
                val text = "第 $index 条消息：滚动时，文字可以从顶部胶囊后面经过。胶囊内部模糊背景，外部保持清晰。"
                MessageNode.of(if (index % 2 == 0) UIMessage.assistant(text) else UIMessage.user(text))
            },
        )
        try {
            repository.insertConversation(conversation)
            settingsStore.update {
                it.copy(
                    dynamicColor = false,
                    developerMode = false,
                    assistantId = assistant.id,
                    assistants = it.assistants + assistant,
                )
            }
            context.writeBooleanPreference("create_new_conversation_on_start", false)
            context.writeStringPreference("lastConversationId", conversation.id.toString())
            context.writeStringPreference("colorMode", ColorMode.LIGHT.name)

            ActivityScenario.launch(RouteActivity::class.java).use {
                compose.waitUntil(timeoutMillis = 20_000) {
                    compose.onAllNodesWithText("悬浮胶囊").fetchSemanticsNodes().isNotEmpty()
                }
                val title = compose.onNodeWithTag("chat_title_capsule").fetchSemanticsNode().boundsInRoot
                val actions = compose.onNodeWithTag("chat_actions_capsule").fetchSemanticsNode().boundsInRoot
                assertTrue("The capsules must have a transparent gap", title.right < actions.left)

                compose.onNodeWithTag("chat_message_list").performScrollToIndex(8)
                compose.onNodeWithTag("chat_message_list").performTouchInput {
                    swipeUp(startY = height * 0.75f, endY = height * 0.45f)
                }
                compose.waitForIdle()
                val messageBounds = compose.onAllNodes(SemanticsMatcher("message row") { node ->
                    node.config.getOrNull(SemanticsProperties.TestTag)?.let { tag ->
                        tag.startsWith("chat_message_") && tag != "chat_message_list"
                    } == true
                }).fetchSemanticsNodes().map { it.boundsInRoot }
                assertTrue(
                    "Messages must scroll into the toolbar area, not stop below a full-width strip",
                    messageBounds.any { it.top < title.bottom && it.bottom > title.top },
                )
                saveScreenshot("chat-glass-scrolled.png")
                saveScreenshot("chat-glass-topbar.png", tag = "chat_floating_topbar")

                compose.onNode(SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription, listOf("Chat Options")
                )).performClick()
                compose.onNodeWithTag("chat_preview_list").performScrollToIndex(12)
                compose.onNodeWithTag("chat_preview_list").performTouchInput {
                    swipeUp(startY = height * 0.75f, endY = height * 0.45f)
                }
                val viewport = compose.onNodeWithTag("chat_preview_list").fetchSemanticsNode().boundsInRoot
                assertTrue("Preview must also use the full viewport", viewport.top < title.top)
                saveScreenshot("chat-glass-preview.png")

                // Use a plain page to catch the original same-color capsule regression,
                // then switch themes in-place to verify that glass tint is not cached.
                compose.onNode(SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription, listOf("Chat Options")
                )).performClick()
                compose.onNodeWithTag("chat_message_list").performScrollToIndex(0)
                val blue = CustomTheme(name = "Test blue", primaryColorArgb = 0xFF1565C0)
                val orange = CustomTheme(name = "Test orange", primaryColorArgb = 0xFFF07828)
                settingsStore.update { settings ->
                    settings.copy(
                        assistants = settings.assistants.map { current ->
                            if (current.id == assistant.id) current.copy(background = null) else current
                        },
                        customThemes = settings.customThemes + listOf(blue, orange),
                    )
                }
                val cases = buildList {
                    add(ThemeCase("minimal-light", MinimalThemePreset.id, ColorMode.LIGHT, MinimalThemePreset.standardLight))
                    add(ThemeCase("custom-blue-light", blue.id, ColorMode.LIGHT, blue.generateColorScheme(false)))
                    add(ThemeCase("custom-orange-light", orange.id, ColorMode.LIGHT, orange.generateColorScheme(false)))
                    add(ThemeCase("custom-orange-dark", orange.id, ColorMode.DARK, orange.generateColorScheme(true)))
                    add(ThemeCase(
                        "custom-orange-amoled", orange.id, ColorMode.DARK,
                        orange.generateColorScheme(true).copy(background = Color.Black, surface = Color.Black),
                        amoled = true,
                    ))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(ThemeCase("dynamic-light", blue.id, ColorMode.LIGHT, dynamicLightColorScheme(context), dynamic = true))
                        add(ThemeCase("dynamic-dark", blue.id, ColorMode.DARK, dynamicDarkColorScheme(context), dynamic = true))
                    }
                }
                for (case in cases) {
                    context.writeStringPreference("colorMode", case.mode.name)
                    context.writeBooleanPreference("amoledDark", case.amoled)
                    settingsStore.update { settings ->
                        settings.copy(themeId = case.themeId, dynamicColor = case.dynamic)
                    }
                    assertThemeAppearance(case)
                    saveScreenshot("chat-glass-${case.name}.png", tag = "chat_floating_topbar")
                }
            }
        } finally {
            koin.get<ChatService>().deleteConversation(conversation)
            settingsStore.update(savedSettings)
            context.writeStringPreference("lastConversationId", savedLastConversation)
            context.writeBooleanPreference("create_new_conversation_on_start", savedCreateNew)
            context.writeStringPreference("colorMode", savedColorMode)
            context.writeBooleanPreference("amoledDark", savedAmoled)
            backdrop.delete()
        }
    }

    private data class ThemeCase(
        val name: String,
        val themeId: String,
        val mode: ColorMode,
        val scheme: ColorScheme,
        val amoled: Boolean = false,
        val dynamic: Boolean = false,
    )

    private fun assertThemeAppearance(case: ThemeCase) {
        val left = compose.onNodeWithTag("chat_title_capsule").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("chat_actions_capsule").fetchSemanticsNode().boundsInRoot
        val expectedPage = case.scheme.background.toArgb()
        val expectedGlass = case.scheme.surfaceContainerHighest.copy(alpha = 0.8f)
            .compositeOver(case.scheme.background).toArgb()
        var page = 0
        var leftGlass = 0
        var rightGlass = 0
        compose.waitUntil(timeoutMillis = 10_000) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            page = sampleColor(bitmap, (left.right + right.left) / 2, left.center.y)
            leftGlass = sampleColor(bitmap, left.center.x, left.top + left.height * 0.08f)
            rightGlass = sampleColor(bitmap, right.center.x, right.top + right.height * 0.08f)
            colorDistance(page, expectedPage) <= 3 &&
                colorDistance(leftGlass, expectedGlass) <= 6 &&
                colorDistance(rightGlass, expectedGlass) <= 6
        }
        for (glass in listOf(leftGlass, rightGlass)) {
            assertTrue(
                "${case.name}: glass must remain distinct from the page without a border or shadow",
                ColorUtils.calculateContrast(glass, page) >= 1.12,
            )
            assertTrue(
                "${case.name}: toolbar text must remain legible",
                ColorUtils.calculateContrast(case.scheme.onSurface.toArgb(), glass) >= 4.5,
            )
        }
    }

    private fun sampleColor(bitmap: Bitmap, x: Float, y: Float): Int {
        val pixels = IntArray(25)
        bitmap.getPixels(pixels, 0, 5, x.toInt() - 2, y.toInt() - 2, 5, 5)
        return android.graphics.Color.rgb(
            pixels.sumOf { android.graphics.Color.red(it) } / pixels.size,
            pixels.sumOf { android.graphics.Color.green(it) } / pixels.size,
            pixels.sumOf { android.graphics.Color.blue(it) } / pixels.size,
        )
    }

    private fun colorDistance(first: Int, second: Int): Int = maxOf(
        abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)),
        abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)),
        abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second)),
    )

    private fun saveScreenshot(name: String, tag: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        val node = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun createBackdrop(file: File) {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 1080f, 2400f,
                intArrayOf(0xFFDAAC89.toInt(), 0xFF99B7B3.toInt(), 0xFFAB9BC3.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, 1080f, 2400f, paint)
        paint.shader = null
        paint.color = 0x44605070
        paint.strokeWidth = 3f
        for (x in 0..1080 step 24) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), 2400f, paint)
        for (y in 0..2400 step 24) canvas.drawLine(0f, y.toFloat(), 1080f, y.toFloat(), paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
