package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.rememberHazeState
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalSharedTransitionScope
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.ui.pages.chat.ChatList
import me.ayuilos.miffan.ui.pages.assistant.detail.AssistantBasicPage
import me.ayuilos.miffan.ui.pages.setting.SettingPreferencesThemePage
import me.ayuilos.miffan.ui.hooks.rememberUserSettingsState
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.MiffanTheme
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.ui.theme.presets.WhaleThemePreset
import me.rerere.ai.ui.UIMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class WhaleGirlVisualTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun actingBeatsChangeAndLoopWithoutAPoseJump() {
        compose.mainClock.autoAdvance = false
        var clip by mutableStateOf(WhaleGirlClip.EATING)
        var seconds by mutableStateOf(0f)
        compose.setContent {
            Box(Modifier.size(168.dp).background(WhaleThemePreset.getColorScheme(false).background)
                .testTag("acting-frame")) {
                WhaleGirlLineArtPortrait(clip, Modifier.size(168.dp), previewSeconds = seconds)
            }
        }
        val periods = mapOf(WhaleGirlClip.EATING to 3.6f, WhaleGirlClip.CHEWING to 3.2f,
            WhaleGirlClip.SLEEPING to 5.6f, WhaleGirlClip.SURPRISE to 1.5f, WhaleGirlClip.THINKING to 2f)
        periods.forEach { (expression, period) ->
            compose.runOnIdle { clip = expression; seconds = 0f }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val sampleTimes = listOf(0f, .12f, .28f, .48f, .68f, .82f)
            val frames = sampleTimes.map { fraction ->
                compose.runOnIdle { seconds = period * fraction }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                capture("acting-frame")
            }
            assertTrue("$expression must have different acting beats",
                frames.drop(1).any { !it.sameAs(frames.first()) })
            val width = frames.first().width
            val height = frames.first().height
            val sheet = Bitmap.createBitmap(width * 3, (height + 50) * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(WhaleThemePreset.getColorScheme(false).background.toArgb())
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.DKGRAY; textSize = 26f }
            frames.forEachIndexed { index, bitmap ->
                val x = (index % 3 * width).toFloat()
                val y = (index / 3 * (height + 50)).toFloat()
                canvas.drawText("${expression.name} ${"%.2f".format(period * sampleTimes[index])}s", x + 16, y + 36, label)
                canvas.drawBitmap(bitmap, x, y + 50, null)
            }
            save("whale-acting-${expression.name.lowercase()}.png", sheet)
            if (expression.looping) {
                compose.runOnIdle { seconds = period }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertTrue("$expression must return to its starting pose at the loop boundary",
                    frames.first().sameAs(capture("acting-frame")))
            }
        }
    }

    private data class Expression(
        val label: String,
        val clip: WhaleGirlClip,
    )

    @Test
    fun renderEveryExpressionAtEverySizeInLightAndDarkThemes() {
        compose.mainClock.autoAdvance = false
        val expressions = WhaleGirlClip.entries.map { Expression(it.name, it) }
        val sizes = listOf(28, 32, 40, 80, 168)
        var dark by mutableStateOf(false)
        var expression by mutableStateOf(expressions.first())
        compose.setContent {
            MaterialTheme(colorScheme = WhaleThemePreset.getColorScheme(dark)) {
                // Static visual cases start at their semantic target. Same-instance updates and
                // lifecycle are checked separately rather than racing pixel capture.
                key(dark, expression) {
                    // Capture one expression at a time: 8 clips × 5 sizes × 2 palettes = 80 cases.
                    Column(
                        modifier = Modifier.testTag("expression")
                            .background(MaterialTheme.colorScheme.background).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(expression.label, color = MaterialTheme.colorScheme.onBackground)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            sizes.take(4).forEach { size ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$size", color = MaterialTheme.colorScheme.onBackground)
                                    WhaleGirlLineArtPortrait(
                                        clip = expression.clip,
                                        playing = false,
                                        dark = dark,
                                        modifier = Modifier.size(size.dp).testTag("size-$size"),
                                        reducedMotion = true,
                                    )
                                }
                            }
                        }
                        Text("168 dp", color = MaterialTheme.colorScheme.onBackground)
                        WhaleGirlLineArtPortrait(
                            clip = expression.clip,
                            playing = false,
                            dark = dark,
                            modifier = Modifier.size(168.dp).testTag("size-168"),
                            reducedMotion = true,
                        )
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            val idlePortraits = mutableMapOf<Int, Bitmap>()
            val galleryPortraits = mutableListOf<Bitmap>()
            val tiles = expressions.map { next ->
                compose.runOnIdle { expression = next }
                compose.mainClock.advanceTimeBy(2_000)
                compose.waitForIdle()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val background = WhaleThemePreset.getColorScheme(isDark).background.toArgb()
                sizes.forEach { size ->
                    val bitmap = capture("size-$size")
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val painted = pixels.count { it != background && it ushr 24 != 0 }
                    assertTrue("${next.label} must remain visible at $size dp (dark=$isDark)",
                        painted > pixels.size / 10)
                    if (next.clip == WhaleGirlClip.IDLE) idlePortraits[size] = bitmap
                    if (next.clip != WhaleGirlClip.IDLE) {
                        val idle = idlePortraits.getValue(size)
                        val idlePixels = IntArray(idle.width * idle.height)
                        idle.getPixels(idlePixels, 0, idle.width, 0, 0, idle.width, idle.height)
                        val different = pixels.indices.count { pixels[it] != idlePixels[it] }
                        assertTrue(
                            "${next.label} must visibly differ from Idle at $size dp (dark=$isDark)",
                            different > pixels.size / 2_000,
                        )
                    }
                }
                if (!isDark && next.clip == WhaleGirlClip.IDLE) {
                    save("whale-girl-native-portrait.png", capture("size-168"))
                }
                galleryPortraits += capture("size-168")
                capture("expression")
            }
            val width = tiles.maxOf { it.width }
            val height = tiles.maxOf { it.height }
            val matrix = Bitmap.createBitmap(width * 2, height * ((tiles.size + 1) / 2), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(matrix)
            canvas.drawColor(WhaleThemePreset.getColorScheme(isDark).background.toArgb())
            tiles.forEachIndexed { index, bitmap ->
                canvas.drawBitmap(bitmap, (index % 2 * width).toFloat(), (index / 2 * height).toFloat(), null)
            }
            save(if (isDark) "whale-girl-native-dark.png" else "whale-girl-native-light.png", matrix)
            // Assemble actual device captures without rescaling or redrawing the character.
            val portraitWidth = galleryPortraits.first().width
            val portraitHeight = galleryPortraits.first().height
            val labelHeight = portraitWidth / 5
            val gallery = Bitmap.createBitmap(portraitWidth * 4,
                (portraitHeight + labelHeight) * ((galleryPortraits.size + 3) / 4), Bitmap.Config.ARGB_8888)
            val galleryCanvas = Canvas(gallery)
            val scheme = WhaleThemePreset.getColorScheme(isDark)
            galleryCanvas.drawColor(scheme.background.toArgb())
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = scheme.onBackground.toArgb()
                textSize = portraitWidth / 12f
                textAlign = Paint.Align.CENTER
            }
            val labels = mapOf(
                WhaleGirlClip.FOCUSED to "聚焦", WhaleGirlClip.TYPING to "打字",
                WhaleGirlClip.SUBMITTED to "收到",
                WhaleGirlClip.IDLE to "微笑", WhaleGirlClip.PETTING to "摸摸",
                WhaleGirlClip.SUCCESS to "得意", WhaleGirlClip.SURPRISE to "提醒",
                WhaleGirlClip.EATING to "吃饭", WhaleGirlClip.CHEWING to "咀嚼",
                WhaleGirlClip.THINKING to "思考", WhaleGirlClip.SLEEPING to "睡觉",
            )
            galleryPortraits.forEachIndexed { index, portrait ->
                val x = (index % 4 * portraitWidth).toFloat()
                val y = (index / 4 * (portraitHeight + labelHeight)).toFloat()
                galleryCanvas.drawText(labels.getValue(expressions[index].clip),
                    x + portraitWidth / 2f, y + labelHeight * .7f, labelPaint)
                galleryCanvas.drawBitmap(portrait, x, y + labelHeight, null)
            }
            save(if (isDark) "whale-girl-approved-gallery-dark.png"
                else "whale-girl-approved-gallery-light.png", gallery)
        }
    }

    @Test
    fun everyClipRedrawsOnTheSameNativeHeadAndReturnsToItsOriginalPixels() {
        compose.mainClock.autoAdvance = false
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        var dark by mutableStateOf(false)
        compose.setContent {
            WhaleGirlLineArtPortrait(
                clip = clip,
                dark = dark,
                reducedMotion = true,
                modifier = Modifier.size(168.dp).testTag("native-changing"),
            )
        }
        for (palette in listOf(false, true)) {
            compose.runOnIdle { dark = palette; clip = WhaleGirlClip.IDLE }
            settleNativeDraw()
            val idle = capture("native-changing")
            val seen = mutableListOf(idle)
            for (next in WhaleGirlClip.entries.filter { it != WhaleGirlClip.IDLE }) {
                compose.runOnIdle { clip = next }
                settleNativeDraw()
                val current = capture("native-changing")
                assertTrue("$next must have distinct pixels on the same native head (dark=$palette)",
                    seen.none { it.sameAs(current) })
                seen += current
            }
            compose.runOnIdle { clip = WhaleGirlClip.IDLE }
            settleNativeDraw()
            assertTrue("Idle must restore deterministic resting pixels (dark=$palette)",
                idle.sameAs(capture("native-changing")))
        }
    }

    @Test
    fun pausedAndReducedMotionNativeClipsStayStill() {
        compose.mainClock.autoAdvance = false
        var clip by mutableStateOf(WhaleGirlClip.IDLE)
        compose.setContent {
            Row {
                WhaleGirlLineArtPortrait(
                    clip = clip,
                    playing = false,
                    modifier = Modifier.size(128.dp).testTag("native-paused"),
                )
                WhaleGirlLineArtPortrait(
                    clip = clip,
                    playing = true,
                    reducedMotion = true,
                    modifier = Modifier.size(128.dp).testTag("native-reduced"),
                )
            }
        }
        for (next in WhaleGirlClip.entries) {
            compose.runOnIdle { clip = next }
            settleNativeDraw()
            val before = listOf("native-paused", "native-reduced").associateWith(::capture)
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            before.forEach { (tag, bitmap) ->
                assertTrue("$next in $tag must stay still after settling", bitmap.sameAs(capture(tag)))
            }
        }
    }

    @Test
    fun nativeChewingChangesTheFaceWithTheAnimationClock() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WhaleGirlLineArtPortrait(
                clip = WhaleGirlClip.CHEWING,
                playing = true,
                modifier = Modifier.size(168.dp).testTag("native-animated"),
            )
        }
        settleNativeDraw()
        val before = captureFace("native-animated")
        val frames = (1..6).map {
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            captureFace("native-animated")
        }
        assertTrue("Chewing must change facial pixels, not only the outer silhouette",
            frames.any { !before.sameAs(it) })
        save("whale-girl-native-chewing.png", capture("native-animated"))
    }

    private fun settleNativeDraw() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun captureFace(tag: String): Bitmap {
        val full = capture(tag)
        // The approved face uses a centered 672 x 650 frame. Keep this crop on the
        // cheeks and mouth so the assertion cannot pass from a moving hair tip or prop.
        val unit = minOf(full.width / 672f, full.height / 650f)
        val left = (full.width - 672f * unit) / 2f
        val top = (full.height - 650f * unit) / 2f
        return Bitmap.createBitmap(full, (left + 200f * unit).toInt(), (top + 500f * unit).toInt(),
            (245f * unit).toInt(), (90f * unit).toInt())
    }

    @Test
    fun semanticChangesRedrawTheSameReducedMotionHead() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(MiffanMascotState.Idle)
        compose.setContent {
            MaterialTheme(colorScheme = WhaleThemePreset.standardLight) {
                WhaleGirlMascot(
                    state = state,
                    modifier = Modifier.size(168.dp).testTag("changing-head"),
                    reducedMotion = true,
                    interactive = false,
                )
            }
        }
        fun settleDraw() {
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        settleDraw()
        val idle = capture("changing-head")
        var previous = idle
        for (next in listOf(MiffanMascotState.Happy, MiffanMascotState.Error)) {
            compose.runOnIdle { state = next }
            settleDraw()
            val current = capture("changing-head")
            assertTrue("The existing head must redraw $next instead of retaining Idle pixels",
                !idle.sameAs(current))
            assertTrue("The existing head must change when entering $next", !previous.sameAs(current))
            previous = current
        }
        compose.runOnIdle { state = MiffanMascotState.Idle }
        settleDraw()
        assertTrue("Returning to Idle must restore the resting face on the same head",
            idle.sameAs(capture("changing-head")))
    }

    @Test
    fun waitingReasoningRespondingAndNightUseDifferentTransparentPortraits() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(MiffanMascotState.Thinking)
        var phase by mutableStateOf(AssistantGenerationPhase.Waiting)
        var dayPhase by mutableStateOf(MiffanDayPhase.Noon)
        compose.setContent {
            MaterialTheme(colorScheme = WhaleThemePreset.standardLight) {
                WhaleGirlMascot(
                    state = state,
                    generationPhase = phase,
                    dayPhase = dayPhase,
                    reducedMotion = true,
                    interactive = false,
                    modifier = Modifier.size(168.dp).testTag("phase-head"),
                )
            }
        }
        val portraits = mutableListOf<Bitmap>()
        for (next in listOf(AssistantGenerationPhase.Waiting, AssistantGenerationPhase.Reasoning,
            AssistantGenerationPhase.Responding)) {
            compose.runOnIdle { phase = next }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            val current = capture("phase-head")
            assertTrue("$next must select its own expression instead of reusing another phase",
                portraits.none { it.sameAs(current) })
            portraits += current
        }
        compose.runOnIdle {
            state = MiffanMascotState.Idle
            phase = AssistantGenerationPhase.None
            dayPhase = MiffanDayPhase.Night
        }
        // Activity wakes the character even at night; she sleeps after the fresh idle interval.
        compose.mainClock.advanceTimeBy(60_500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("蓝色大肥鱼，睡着了").assertExists()
        assertTrue("Nighttime idle must select the sleeping expression",
            portraits.none { it.sameAs(capture("phase-head")) })
    }

    @Test
    fun themeSettingsCreatesAnIndependentWhaleAssistant() {
        // The real settings card now loops authored animation; drive time explicitly instead
        // of asking Espresso to wait for a continuously animated screen to become idle.
        compose.mainClock.autoAdvance = false
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlowRaw.first() }
        val assistant = Assistant(name = "蓝鱼主题测试")
        val other = Assistant(name = "保留原头像", avatar = Avatar.Emoji("🌱"), systemPrompt = "Keep my settings")
        try {
            runBlocking {
                store.update(original.copy(
                    themeId = "ocean",
                    dynamicColor = true,
                    assistants = original.assistants + assistant + other,
                    assistantId = assistant.id,
                ))
            }
            compose.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalNavController provides remember { Navigator(mutableListOf()) },
                    ) {
                        Box(Modifier.fillMaxSize().testTag("theme-settings")) {
                            SettingPreferencesThemePage()
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("应用蓝鱼主题").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                store.settingsFlow.value.themeId == WHALE_THEME_ID && !store.settingsFlow.value.dynamicColor
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("一键体验蓝鱼主题").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                store.settingsFlow.value.whaleThemeDiscovery.dedicatedAssistantId != null
            }
            val persisted = runBlocking { store.settingsFlowRaw.first() }
            val whaleId = persisted.whaleThemeDiscovery.dedicatedAssistantId
            assertEquals(WHALE_THEME_ID, persisted.themeId)
            assertEquals(false, persisted.dynamicColor)
            assertEquals(whaleId, persisted.assistantId)
            assertEquals(original.assistants + assistant + other, persisted.assistants.filter { it.id != whaleId })
            compose.mainClock.advanceTimeBy(500)
            listOf("微笑", "摸摸", "开心", "提醒", "扒饭", "嚼饭", "推理", "睡觉").forEach { label ->
                compose.onNodeWithText(label).performScrollTo().assertExists()
            }
            compose.onNodeWithText("开心").performScrollTo().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            save("whale-girl-settings.png", capture("theme-settings"))
        } finally {
            runBlocking { store.update(original) }
        }
    }

    @Test
    fun whaleAssistantSettingsExposeOnePersonalityWhileMiffanKeepsItsEditor() {
        compose.mainClock.autoAdvance = false
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlowRaw.first() }
        val assistant = Assistant(name = "单一蓝鱼性格测试", avatar = Avatar.WhaleGirl())
        try {
            runBlocking { store.update(original.copy(assistants = original.assistants + assistant)) }
            compose.setContent {
                val settings by rememberUserSettingsState()
                MiffanTheme(colorMode = ColorMode.LIGHT) {
                    SharedTransitionLayout {
                        val sharedScope = this
                        AnimatedContent(targetState = assistant.id, label = "assistant-settings-test") { id ->
                            CompositionLocalProvider(
                                LocalSettings provides settings,
                                LocalNavController provides remember { Navigator(mutableListOf()) },
                                LocalToaster provides rememberToasterState(),
                                LocalSharedTransitionScope provides sharedScope,
                                LocalNavAnimatedContentScope provides this,
                            ) {
                                AssistantBasicPage(id.toString())
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithText(WHALE_PERSONALITY).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(WHALE_PERSONALITY).assertExists()
            compose.onNodeWithText("动作性格").assertDoesNotExist()

            compose.onNodeWithText("Miffan 饭碗").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                store.settingsFlow.value.assistants.first { it.id == assistant.id }.avatar is Avatar.Miffan
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("动作性格").performScrollTo().assertExists()

            compose.onNodeWithText("蓝色大肥鱼").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                store.settingsFlow.value.assistants.first { it.id == assistant.id }.avatar is Avatar.WhaleGirl
            }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText(WHALE_PERSONALITY).assertExists()
            compose.onNodeWithText("动作性格").assertDoesNotExist()
        } finally {
            runBlocking { store.update(original) }
        }
    }

    @Test
    fun historicalIdleAndReducedThinkingRemainStillAfterClockAdvances() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = WhaleThemePreset.standardLight) {
                Row {
                    WhaleGirlMascot(
                        state = MiffanMascotState.Idle,
                        modifier = Modifier.size(128.dp).testTag("historical"),
                        presentation = MiffanPresentation.Avatar,
                        interactive = false,
                    )
                    WhaleGirlMascot(
                        state = MiffanMascotState.Thinking,
                        modifier = Modifier.size(128.dp).testTag("reduced"),
                        reducedMotion = true,
                        interactive = false,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val before = listOf("historical", "reduced").associateWith(::capture)
        compose.mainClock.advanceTimeBy(10_000)
        before.forEach { (tag, bitmap) ->
            assertTrue("$tag must not blink, drift, or bob after settling", bitmap.sameAs(capture(tag)))
        }
    }

    @Test
    fun chatListKeepsOneWhaleThroughHandoffAndRestoresItForANewConversation() {
        compose.mainClock.autoAdvance = false
        val assistant = Assistant(name = "Blue whale test", avatar = Avatar.WhaleGirl(), useAssistantAvatar = true)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        var conversation by mutableStateOf(Conversation(assistantId = assistant.id, messageNodes = emptyList()))
        var loading by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = WhaleThemePreset.standardLight) {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalNavController provides remember { Navigator(mutableListOf()) },
                    LocalToaster provides rememberToasterState(),
                ) {
                    ChatList(
                        innerPadding = PaddingValues(top = 64.dp, bottom = 112.dp),
                        conversation = conversation,
                        state = rememberLazyListState(),
                        loading = loading,
                        previewMode = false,
                        settings = settings,
                        hazeState = rememberHazeState(),
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                            .testTag("chat-scene"),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription(WHALE_DESCRIPTION, substring = true).assertCountEquals(1)
        val initial = compose.onNodeWithContentDescription(WHALE_DESCRIPTION, substring = true).fetchSemanticsNode().boundsInRoot
        save("whale-girl-chat-empty.png", capture("chat-scene"))
        compose.runOnIdle {
            conversation = conversation.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("你好，大肥鱼"))))
            loading = true
        }
        compose.mainClock.advanceTimeBy(112)
        compose.onAllNodesWithContentDescription(WHALE_DESCRIPTION, substring = true).assertCountEquals(1)
        val moving = compose.onNodeWithContentDescription(WHALE_DESCRIPTION, substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The whale head shrinks during the first-send handoff", moving.width < initial.width)
        save("whale-girl-chat-handoff.png", capture("chat-scene"))
        compose.mainClock.advanceTimeBy(1_200)
        val waiting = compose.onNodeWithContentDescription(WHALE_DESCRIPTION, substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The waiting head fits the avatar slot", waiting.width < initial.width / 2)
        save("whale-girl-chat-waiting.png", capture("chat-scene"))
        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription(WHALE_DESCRIPTION, substring = true).assertCountEquals(0)
        compose.runOnIdle { conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()) }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription(WHALE_DESCRIPTION, substring = true).assertCountEquals(1)
    }

    private fun capture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        .copy(Bitmap.Config.ARGB_8888, false)

    private fun save(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val WHALE_DESCRIPTION = "蓝色大肥鱼"
        const val WHALE_PERSONALITY = "蓝色大肥鱼 · 爱吃饭、有点嘴硬，也会认真听你说话。"
    }
}
