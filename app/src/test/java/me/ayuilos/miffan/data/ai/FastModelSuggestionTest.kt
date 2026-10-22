package me.ayuilos.miffan.data.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class FastModelSuggestionTest {
    private val tiers = listOf("flash-lite", "nano", "haiku", "mini", "lite", "flash", "small")

    @Test
    fun eachTierMatchesModelIdOrDisplayNameIgnoringCase() {
        tiers.forEach { tier ->
            val byId = Model(modelId = "provider/MODEL-${tier.uppercase()}")
            val byName = Model(modelId = "opaque", displayName = "Model ${tier.uppercase()}")
            listOf(byId, byName).forEach { model ->
                assertEquals(model, ProviderSetting.OpenAI(models = listOf(model)).suggestFastModel(null))
            }
        }
    }

    @Test
    fun tierPriorityWinsOverProviderOrderAndProviderOrderBreaksTies() {
        val models = tiers.reversed().map { Model(modelId = "model-$it") }
        tiers.forEachIndexed { index, tier ->
            val candidates = models.filter { model -> tiers.take(index).none { model.modelId == "model-$it" } }
            assertEquals("model-$tier", ProviderSetting.OpenAI(models = candidates).suggestFastModel(null)?.modelId)
        }
        val first = Model(modelId = "first-mini")
        val second = Model(modelId = "second-mini")
        assertEquals(first, ProviderSetting.OpenAI(models = listOf(first, second)).suggestFastModel(null))
    }

    @Test
    fun chatModelIsExcludedByIdEvenWhenItsNameWins() {
        val chat = Model(modelId = "chat-flash-lite")
        val mini = Model(modelId = "gpt-4o-mini")
        val provider = ProviderSetting.OpenAI(models = listOf(chat.copy(displayName = "duplicate"), mini))
        assertEquals(mini, provider.suggestFastModel(chat))
        assertNull(provider.copy(models = listOf(chat)).suggestFastModel(chat))
    }

    @Test
    fun nonChatTypesAndSkipWordsAreExcludedInBothNames() {
        val excluded = ModelType.entries.filter { it != ModelType.CHAT }.map { Model(modelId = "nano", type = it) } +
            listOf("image", "tts", "audio", "embed", "realtime", "transcribe").flatMap { word ->
                listOf(Model(modelId = "$word-nano"), Model(modelId = "nano", displayName = word.uppercase()))
            }
        val valid = Model(modelId = "gpt-mini")
        assertEquals(valid, ProviderSetting.OpenAI(models = excluded + valid).suggestFastModel(null))
        assertNull(ProviderSetting.OpenAI(models = excluded).suggestFastModel(null))
    }

    @Test
    fun miniRequiresLetterBoundaries() {
        val falseMatches = listOf("gemini-2.5-pro", "MiniMax-M1", "notmini", "minibus").map { Model(modelId = it) }
        assertNull(ProviderSetting.OpenAI(models = falseMatches).suggestFastModel(null))
        listOf("gpt-4o-mini", "o4-mini", "mini", "mini-2026").forEach { name ->
            val mini = Model(modelId = name)
            assertEquals(mini, ProviderSetting.OpenAI(models = falseMatches + mini).suggestFastModel(null))
        }
    }

    @Test
    fun noLightModelReturnsNull() {
        assertNull(ProviderSetting.OpenAI(models = listOf(Model(modelId = "pro"))).suggestFastModel(null))
        assertNull(ProviderSetting.OpenAI().suggestFastModel(null))
    }

    @Test
    fun chatModelsThatDrawAreNotSuggestedEvenWithAFastName() {
        val drawing = Model(modelId = "google/gemini-flash-lite-preview", outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val text = Model(modelId = "gpt-4o-mini")
        assertEquals(text, ProviderSetting.OpenAI(models = listOf(drawing, text)).suggestFastModel(null))
        assertNull(ProviderSetting.OpenAI(models = listOf(drawing)).suggestFastModel(null))
    }
}
