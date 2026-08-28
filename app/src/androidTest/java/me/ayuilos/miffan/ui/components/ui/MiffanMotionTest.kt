package me.ayuilos.miffan.ui.components.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.ui.context.LocalToaster
import com.dokar.sonner.rememberToasterState
import me.ayuilos.miffan.ui.pages.chat.ChatList
import me.rerere.ai.ui.UIMessage
import dev.chrisbanes.haze.rememberHazeState
import me.ayuilos.miffan.service.AssistantReplyCompleted
import me.ayuilos.miffan.ui.pages.chat.rememberCompletedMascotReply
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MiffanMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chatListHandoffSurvivesFirstMessageCancellationAndConversationChange() {
        compose.mainClock.autoAdvance = false
        val assistant = Assistant(name = "Miffan motion test")
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        var conversation by mutableStateOf(Conversation(assistantId = assistant.id, messageNodes = emptyList()))
        var loading by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
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
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("chat-scene"),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription("Miffan mascot").assertCountEquals(1)
        val initial = compose.onNodeWithContentDescription("Miffan mascot").fetchSemanticsNode().boundsInRoot
        saveMatrix("miffan-chat-empty.png", "chat-scene")
        compose.runOnIdle {
            conversation = conversation.copy(messageNodes = listOf(MessageNode.of(UIMessage.user("看看吉祥物的状态衔接"))))
            loading = true
        }
        compose.mainClock.advanceTimeBy(112)
        compose.onAllNodesWithContentDescription("Miffan mascot").assertCountEquals(1)
        val moving = compose.onNodeWithContentDescription("Miffan mascot").fetchSemanticsNode().boundsInRoot
        assertTrue(moving.width < initial.width)
        saveMatrix("miffan-chat-handoff.png", "chat-scene")
        compose.mainClock.advanceTimeBy(1_200)
        val waiting = compose.onNodeWithContentDescription("Miffan mascot").fetchSemanticsNode().boundsInRoot
        assertTrue(waiting.width < initial.width / 2)
        saveMatrix("miffan-chat-waiting.png", "chat-scene")
        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription("Miffan mascot").assertCountEquals(0)
        compose.runOnIdle { conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()) }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithContentDescription("Miffan mascot").assertCountEquals(1)
    }

    @Test
    fun handoffKeepsOneMascotAndTracksTheWaitingSlot() {
        compose.mainClock.autoAdvance = false
        val handoff = MiffanHandoffState()
        var destination by mutableStateOf<MiffanHandoffDestination?>(MiffanHandoffDestination.EmptyChat)
        var waitingY by mutableIntStateOf(36)
        var reduced by mutableStateOf(false)
        var instances = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 480.dp).testTag("handoff").background(MaterialTheme.colorScheme.background)) {
                    if (destination == MiffanHandoffDestination.EmptyChat) {
                        MiffanHandoffAnchor(handoff, MiffanHandoffDestination.EmptyChat,
                            Modifier.size(168.dp).align(Alignment.Center).testTag("empty-slot"))
                    }
                    if (destination == MiffanHandoffDestination.WaitingReply) {
                        MiffanHandoffAnchor(handoff, MiffanHandoffDestination.WaitingReply,
                            Modifier.offset(20.dp, waitingY.dp).size(48.dp).testTag("waiting-slot"))
                    }
                    MiffanHandoff(handoff, destination, Modifier.fillMaxSize(), reduced) { modifier, _ ->
                        remember { ++instances }
                        MiffanMascot(
                            if (destination == MiffanHandoffDestination.WaitingReply) MiffanMascotState.Thinking else MiffanMascotState.Idle,
                            modifier.testTag("traveller"),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val start = compose.onNodeWithTag("traveller").fetchSemanticsNode().boundsInRoot
        saveMatrix("miffan-handoff-empty.png", "handoff")
        compose.runOnIdle { destination = MiffanHandoffDestination.WaitingReply }
        compose.mainClock.advanceTimeBy(112)
        val during = compose.onNodeWithTag("traveller").fetchSemanticsNode().boundsInRoot
        val waiting = compose.onNodeWithTag("waiting-slot").fetchSemanticsNode().boundsInRoot
        assertTrue("The same mascot must shrink between its two slots", during.width < start.width && during.width > waiting.width)
        saveMatrix("miffan-handoff-mid.png", "handoff")
        compose.mainClock.advanceTimeBy(1_200)
        assertBoundsMatch("traveller", "waiting-slot")
        saveMatrix("miffan-handoff-waiting.png", "handoff")
        compose.runOnIdle { waitingY = 180 }
        compose.mainClock.advanceTimeBy(64)
        assertBoundsMatch("traveller", "waiting-slot")
        compose.runOnIdle { reduced = true; destination = MiffanHandoffDestination.EmptyChat }
        compose.mainClock.advanceTimeBy(64)
        assertBoundsMatch("traveller", "empty-slot")
        compose.runOnIdle { destination = null }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { destination = MiffanHandoffDestination.WaitingReply }
        compose.mainClock.advanceTimeBy(64)
        assertBoundsMatch("traveller", "waiting-slot")
        compose.runOnIdle { assertEquals("No face instance may be recreated", 1, instances) }
    }

    @Test
    fun historicalAvatarsAndReducedThinkingStopAmbientMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Row {
                    MiffanMascot(MiffanMascotState.Idle, Modifier.size(128.dp).testTag("static-avatar"),
                        presentation = MiffanPresentation.Avatar)
                    MiffanMascot(MiffanMascotState.Thinking, Modifier.size(128.dp).testTag("reduced-thinking"), reducedMotion = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val before = listOf("static-avatar", "reduced-thinking").associateWith {
            compose.onNodeWithTag(it).captureToImage().asAndroidBitmap()
        }
        compose.mainClock.advanceTimeBy(10_000)
        before.forEach { (tag, bitmap) ->
            assertTrue("$tag must not blink, drift, or bob while settled",
                bitmap.sameAs(compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()))
        }
    }

    @Test
    fun systemAnimationSettingIsObservedWithoutReopeningTheScreen() {
        compose.mainClock.autoAdvance = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = android.provider.Settings.Global.getString(
            instrumentation.targetContext.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        )
        fun setScale(value: String?) {
            val command = if (value == null) "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        var reduced = false
        try {
            setScale("1")
            compose.setContent { reduced = rememberMiffanReducedMotion() }
            compose.mainClock.advanceTimeBy(64)
            setScale("0")
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                reduced
            }
            setScale("1")
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                !reduced
            }
        } finally {
            setScale(previous)
        }
    }

    @Test
    fun onlyConfirmedSuccessCelebratesAndTheNextTurnClearsIt() {
        compose.mainClock.autoAdvance = false
        val conversationId = Uuid.random()
        val messageId = Uuid.random()
        val events = MutableSharedFlow<AssistantReplyCompleted>(extraBufferCapacity = 8)
        val jobs = MutableStateFlow<Job?>(null)
        lateinit var reply: State<Uuid?>
        compose.setContent { reply = rememberCompletedMascotReply(conversationId, events, jobs, 600L) }
        val successful = Job()
        compose.runOnIdle {
            jobs.value = successful
            events.tryEmit(AssistantReplyCompleted(conversationId, messageId, successful))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertNull(reply.value); successful.complete() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertNull(reply.value); jobs.value = null }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(messageId, reply.value) }
        val next = Job()
        compose.runOnIdle { jobs.value = next }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertNull(reply.value); next.cancel(); jobs.value = null }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertNull(reply.value) }

        val cancelled = Job()
        compose.runOnIdle {
            jobs.value = cancelled
            events.tryEmit(AssistantReplyCompleted(conversationId, messageId, cancelled))
            cancelled.cancel()
            jobs.value = null
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertNull(reply.value) }
        val queued = Job()
        compose.runOnIdle {
            jobs.value = queued
            events.tryEmit(AssistantReplyCompleted(conversationId, messageId, Job().apply { complete() }))
            events.tryEmit(AssistantReplyCompleted(Uuid.random(), messageId, Job().apply { complete() }))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertNull(reply.value); queued.cancel(); jobs.value = null }
        compose.runOnIdle {
            events.tryEmit(AssistantReplyCompleted(conversationId, messageId, Job().apply { complete() }))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(messageId, reply.value) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertNull(reply.value) }
    }

    private fun assertBoundsMatch(first: String, second: String) {
        val actual = compose.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
        val expected = compose.onNodeWithTag(second).fetchSemanticsNode().boundsInRoot
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.width, actual.width, 1f)
        assertEquals(expected.height, actual.height, 1f)
    }

    @Test
    fun faceRetargetsFromTheDisplayedExpression() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(MiffanMascotState.Idle)
        lateinit var face: State<MiffanFacePose>
        compose.setContent {
            face = animateMiffanFace(state, MiffanMascotInputState.Inactive, MiffanMotionProfile.CURIOUS.miffanMotionTuning())
        }
        compose.runOnIdle { state = MiffanMascotState.Happy }
        compose.mainClock.advanceTimeBy(144)
        var before = MiffanFacePose()
        compose.runOnIdle {
            before = face.value
            assertTrue(before.mouthCurve > 0.1f && before.mouthCurve < 0.95f)
            state = MiffanMascotState.Error
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("Retargeting must not snap to the new face", abs(face.value.mouthCurve - before.mouthCurve) < 0.2f)
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.runOnIdle {
            assertEquals(-0.8f, face.value.mouthCurve, 0.01f)
            assertEquals(4f, face.value.mouthOpen, 0.05f)
        }
    }

    @Test
    fun repeatedAttentionKeepsItsPoseAndEventuallySettles() {
        compose.mainClock.autoAdvance = false
        var eventId by mutableIntStateOf(0)
        var target by mutableStateOf(Offset(-4f, -2f))
        var profile by mutableStateOf(MiffanMotionProfile.CURIOUS)
        lateinit var attention: MiffanAttentionAnimation
        compose.setContent {
            attention = rememberMiffanAttention(eventId, target, profile.miffanMotionTuning())
        }
        compose.runOnIdle { eventId++ }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertNotNull(attention.lookAt)
            assertEquals("Eyes lead the body", 0f, attention.tilt.value, 0.001f)
        }
        compose.mainClock.advanceTimeBy(240)
        var priorTilt = 0f
        compose.runOnIdle {
            priorTilt = attention.tilt.value
            assertTrue(priorTilt > 0.2f)
            target = Offset(4f, 2f)
            eventId++
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertTrue("A second tap must not reset the body", abs(attention.tilt.value - priorTilt) < 0.35f)
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.runOnIdle {
            assertNull(attention.lookAt)
            assertEquals(0f, attention.expression.value, 0.01f)
            assertEquals(0f, attention.tilt.value, 0.01f)
            assertEquals(1f, attention.squash.value, 0.001f)
            profile = MiffanMotionProfile.CALM
        }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle {
            assertNull("Changing profile must not replay an old tap", attention.lookAt)
            assertEquals(0f, attention.expression.value, 0.01f)
        }
    }

    @Test
    fun mouthContoursStayRoundWhileExpressionsMorph() {
        fun rasterize(path: android.graphics.Path): IntArray {
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                translate(192f, 192f)
                scale(12f, 12f)
                translate(-100f, -128f)
                drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
            }
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        }

        val neutralOvals = listOf(
            MiffanMascotState.Idle to android.graphics.RectF(95f, 124f, 105f, 132f),
            MiffanMascotState.Thinking to android.graphics.RectF(93.5f, 121.5f, 106.5f, 134.5f),
        )
        neutralOvals.forEach { (state, bounds) ->
            val actual = rasterize(miffanMouthPath(miffanFacePose(state, MiffanMascotInputState.Inactive)).asAndroidPath())
            val expected = rasterize(android.graphics.Path().apply { addOval(bounds, android.graphics.Path.Direction.CW) })
            val difference = actual.indices.sumOf { abs((actual[it] ushr 24) - (expected[it] ushr 24)).toLong() }
            val area = expected.sumOf { (it ushr 24).toLong() }
            assertTrue("$state must render as an oval, not a pointed almond", difference < area * 0.01)
        }

        val poses = MiffanMascotState.entries.map { miffanFacePose(it, MiffanMascotInputState.Inactive) } +
            MiffanMascotInputState.entries.map { miffanFacePose(MiffanMascotState.Idle, it) } + MiffanFacePose.Attention
        poses.zip(poses.drop(1) + poses.first()).forEach { (from, to) ->
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (yawn in listOf(0f, 0.5f, 1f)) {
                    val path = android.graphics.PathMeasure(miffanMouthPath(from.blendTo(to, fraction), yawn).asAndroidPath(), true)
                    val start = FloatArray(2)
                    val end = FloatArray(2)
                    assertTrue(path.getPosTan(0f, null, start))
                    assertTrue(path.getPosTan(path.length, null, end))
                    assertTrue("The mouth must close with a smooth vertical tangent, even mid-expression",
                        abs(start[0]) < 0.01f && abs(end[0]) < 0.01f && start[1] < -0.99f && end[1] < -0.99f)
                }
            }
        }
    }

    @Test
    fun renderRoundMouthsAtChatAndAvatarSizes() {
        compose.mainClock.autoAdvance = false
        var dark by mutableStateOf(false)
        val expressions = listOf(
            Triple("普通", MiffanMascotState.Idle, MiffanMascotInputState.Inactive),
            Triple("思考", MiffanMascotState.Thinking, MiffanMascotInputState.Inactive),
            Triple("输入框聚焦", MiffanMascotState.Idle, MiffanMascotInputState.Focused),
            Triple("正在输入", MiffanMascotState.Idle, MiffanMascotInputState.Typing),
            Triple("开心", MiffanMascotState.Happy, MiffanMascotInputState.Inactive),
            Triple("难过", MiffanMascotState.Error, MiffanMascotInputState.Inactive),
        )
        val sizes = listOf(28, 32, 40, 80)
        val neutralStates = listOf(MiffanMascotState.Idle, MiffanMascotState.Thinking)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(Modifier.testTag("round-mouths").background(MaterialTheme.colorScheme.background).padding(8.dp)) {
                    expressions.chunked(2).forEachIndexed { index, row ->
                        Row(Modifier.testTag("mouth-expressions-$index")) {
                            row.forEach { (label, state, input) ->
                                Column {
                                    Text(label, color = MaterialTheme.colorScheme.onBackground)
                                    MiffanMascot(state, Modifier.size(168.dp), inputState = input, reducedMotion = true)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sizes.forEach { size ->
                            Column {
                                Text("$size dp", color = MaterialTheme.colorScheme.onBackground)
                                neutralStates.forEach { state ->
                                    MiffanMascot(state, Modifier.size(size.dp).testTag("mouth-$state-$size"),
                                        reducedMotion = true, presentation = MiffanPresentation.Avatar)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            compose.mainClock.advanceTimeByFrame()
            sizes.forEach { size ->
                neutralStates.forEach { state ->
                    val bitmap = compose.onNodeWithTag("mouth-$state-$size").captureToImage().asAndroidBitmap()
                    val faceColor = MiffanPalette.CLASSIC.miffanColors().face.toArgb()
                    val mouthPixels = (bitmap.height * 3 / 5 until bitmap.height * 7 / 10).sumOf { y ->
                        (bitmap.width * 9 / 20 until bitmap.width * 11 / 20).count { x -> bitmap.getPixel(x, y) == faceColor }
                    }
                    assertTrue("The $state mouth must remain visible at $size dp", mouthPixels > 0)
                }
            }
            saveMatrix(if (isDark) "miffan-round-mouth-dark.png" else "miffan-round-mouth-light.png", "round-mouths")
            if (!isDark) saveMatrix("miffan-round-mouth-preview.png", "mouth-expressions-0")
        }
    }

    @Test
    fun renderExpressionsAndAvatarSizesInBothThemes() {
        compose.mainClock.autoAdvance = false
        var dark by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        val sizes = listOf(28, 32, 40, 80, 168)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(
                    modifier = Modifier.testTag("matrix")
                        .background(MaterialTheme.colorScheme.background).padding(8.dp),
                ) {
                    MiffanMascotState.entries.forEach { state ->
                        Text(state.name, color = MaterialTheme.colorScheme.onBackground)
                        Row {
                            MiffanKind.entries.forEach { kind ->
                                MiffanMascot(
                                    state = state,
                                    appearance = MiffanAppearance(kind = kind),
                                    reducedMotion = reduced,
                                    modifier = Modifier.size(72.dp),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        sizes.forEach { size ->
                            Column {
                                Text("$size", color = MaterialTheme.colorScheme.onBackground)
                                MiffanMascot(
                                    state = MiffanMascotState.Happy,
                                    reducedMotion = reduced,
                                    modifier = Modifier.size(size.dp).testTag("size-$size"),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            compose.mainClock.advanceTimeByFrame()
            sizes.forEach { size ->
                val bitmap = compose.onNodeWithTag("size-$size").captureToImage().asAndroidBitmap()
                val faceColor = MiffanPalette.CLASSIC.miffanColors().face.toArgb()
                var facePixels = 0
                for (y in bitmap.height / 2 until bitmap.height * 3 / 4) {
                    for (x in bitmap.width / 4 until bitmap.width * 3 / 4) {
                        if (bitmap.getPixel(x, y) == faceColor) facePixels++
                    }
                }
                assertTrue("Face must remain visible at $size dp", facePixels > 0)
            }
            saveMatrix(if (isDark) "miffan-motion-dark.png" else "miffan-motion-light.png")
        }
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeBy(800)
        saveMatrix("miffan-motion-reduced.png")
    }

    private fun saveMatrix(name: String, tag: String = "matrix") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-tests").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
