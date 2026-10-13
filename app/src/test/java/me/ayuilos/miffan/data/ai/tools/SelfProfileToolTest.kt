package me.ayuilos.miffan.data.ai.tools

import android.content.Context
import android.content.res.AssetManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.*
import me.ayuilos.miffan.utils.EmojiUtils
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class SelfProfileToolTest {
    private val a = Assistant(name = "A", systemPrompt = "old")
    private val b = Assistant(name = "B", systemPrompt = "B private")
    private val access = AgentAccess.forAssistant(a, true)
    private val trigger = MessageRef(Uuid.random(), Uuid.random())
    private val emojiValues by lazy {
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.open("emoji/categories.with.modifiers.min.json") } answers {
            File("src/main/assets/emoji/categories.with.modifiers.min.json").inputStream()
        }
        profileEmojiValues(EmojiUtils.loadEmoji(context))
    }
    private fun tools(store: SettingsStore, authority: AgentAccess = access, im: Boolean = true, ref: MessageRef? = trigger) =
        buildSelfProfileTools(authority, im, store, ref, mockk()) { emojiValues }
    private fun List<UIMessagePart>.result() = Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject

    @Test fun registrationRequiresImAndOwnerAndEveryCallRequiresApproval() = runTest {
        val store = mockk<SettingsStore>()
        assertTrue(tools(store, access.copy(ownerInitiated = false)).isEmpty())
        assertTrue(tools(store, im = false).isEmpty())
        assertTrue(tools(store, ref = null).isEmpty())
        val tool = tools(store).single()
        assertEquals("update_my_profile", tool.name)
        for (input in listOf(JsonObject(emptyMap()), JsonNull, buildJsonObject { put("name", "X"); put("reason", "改名字") })) {
            assertTrue(tool.needsApproval(input))
        }
        assertFalse(tool.parameters().toString().contains("assistant_id"))
    }

    @Test fun invalidInputsReturnErrorsWithoutWriting() = runTest {
        val store = mockk<SettingsStore>()
        val tool = tools(store).single()
        val invalid = listOf(
            "{}", "[]", "null", """{"reason":"why"}""", """{"name":"X"}""",
            """{"name":"X","reason":"   "}""", """{"name":null,"reason":"why"}""",
            """{"name":42,"reason":"why"}""", """{"name":[],"reason":"why"}""",
            """{"name":"  ","reason":"why"}""", """{"name":"X","reason":true}""",
            """{"name":"X","reason":"why","assistant_id":"${b.id}"}""",
        ).map { Json.parseToJsonElement(it) } + listOf(
            buildJsonObject { put("name", "x".repeat(41)); put("reason", "why") },
            buildJsonObject { put("system_prompt", "x".repeat(8001)); put("reason", "why") },
        ) + listOf("ab", "😀😀", "", " ", "👍🏽👍", "a\u0301", "\u200D", "🏽", "😀\uFE0E", "🇨\uFE0F🇳", "😀\uFE0F\uFE0F", "\uFE0F😀").map {
            buildJsonObject { put("avatar_emoji", it); put("reason", "why") }
        }
        invalid.forEach { input ->
            val result = tool.execute(input).result()
            assertFalse("$input", result.getValue("success").jsonPrimitive.boolean)
            assertTrue(result.containsKey("error"))
        }
        coVerify(exactly = 0) { store.update(any<(Settings) -> Settings>()) }
    }

    @Test fun updatesOnlyCallerUsingLatestSettingsAndDoesNotEchoPrompt() = runTest {
        val state = MutableStateFlow(Settings(assistants = listOf(a, b)))
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            state.value = firstArg<(Settings) -> Settings>()(state.value)
        }
        val tool = tools(store).single()
        val latest = a.copy(learnedPreferences = "changed while waiting", temperature = 0.7f)
        state.value = state.value.copy(assistants = listOf(latest, b))
        val result = tool.execute(buildJsonObject {
            put("name", " X "); put("avatar_emoji", "🐳"); put("system_prompt", "secret new prompt"); put("reason", "改资料")
        }).result()
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        assertEquals(listOf("name", "avatar_emoji", "system_prompt"), result.getValue("changed").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Changes apply from your next reply.", result.getValue("note").jsonPrimitive.content)
        assertFalse(result.toString().contains("secret new prompt"))
        assertEquals(latest.copy(name = "X", avatar = Avatar.Emoji("🐳"), systemPrompt = "secret new prompt"), state.value.assistants.first())
        assertEquals(b, state.value.assistants.last())
    }

    @Test fun acceptsEmojiClustersAndLengthBoundariesAndEmptyPrompt() = runTest {
        val state = MutableStateFlow(Settings(assistants = listOf(a)))
        val store = mockk<SettingsStore>()
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            state.value = firstArg<(Settings) -> Settings>()(state.value)
        }
        val tool = tools(store).single()
        listOf("👍🏽", "👨‍👩‍👧", "🇨🇳", "1️⃣", "❤️", "🐳").forEach { emoji ->
            assertTrue(emoji, tool.execute(buildJsonObject { put("avatar_emoji", emoji); put("reason", "头像") }).result().getValue("success").jsonPrimitive.boolean)
            assertEquals(Avatar.Emoji(emoji), state.value.assistants.single().avatar)
        }
        for (name in listOf("x", "x".repeat(40), "🐳".repeat(40))) {
            assertTrue(tool.execute(buildJsonObject { put("name", name); put("reason", "名字") }).result().getValue("success").jsonPrimitive.boolean)
        }
        for (prompt in listOf("", "x".repeat(8000))) {
            assertTrue(tool.execute(buildJsonObject { put("system_prompt", prompt); put("reason", "设定") }).result().getValue("success").jsonPrimitive.boolean)
            assertEquals(prompt, state.value.assistants.single().systemPrompt)
        }
        state.value = state.value.copy(assistants = listOf(b))
        assertFalse(tool.execute(buildJsonObject { put("name", "gone"); put("reason", "名字") }).result().getValue("success").jsonPrimitive.boolean)
        assertEquals(listOf(b), state.value.assistants)
    }
}
