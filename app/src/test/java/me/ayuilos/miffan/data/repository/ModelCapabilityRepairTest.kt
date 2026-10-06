package me.ayuilos.miffan.data.repository

import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.DiscoveredModelCapabilities
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ReasoningCapabilities
import me.rerere.ai.registry.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelCapabilityRepairTest {
    private val catalog = ModelCatalog.parseModelsDev(
        """{"lab/new-model":{"tool_call":true,"reasoning":true,"modalities":{"input":["text","image","pdf"],"output":["text"]}}}""",
        null,
    )

    @Test
    fun `unknown models repaired across providers preserving unrelated metadata`() {
        val model = Model(
            modelId = "gateway/new-model", displayName = "My model",
            reasoningCapabilities = ReasoningCapabilities(defaultEffort = "high"),
            tools = setOf(BuiltInTools.Search),
            discoveredCapabilities = DiscoveredModelCapabilities(abilities = emptyList()),
        )
        val registryModel = Model(modelId = "codex/gpt-6.1-sol")
        val settings = Settings(providers = listOf(
            ProviderSetting.OpenAI(models = listOf(model)),
            ProviderSetting.Google(models = listOf(registryModel)),
            ProviderSetting.Claude(models = listOf(model.copy(modelId = "new-model"))),
        ))
        val repaired = settings.repairModelCapabilities(catalog)
        repaired.providers.forEach { provider ->
            assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), provider.models.single().abilities)
            assertEquals(listOf(Modality.TEXT, Modality.IMAGE), provider.models.single().inputModalities)
        }
        assertEquals(model.copy(
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        ), repaired.providers.first().models.single())
        assertSame(repaired, repaired.repairModelCapabilities(catalog))
    }

    @Test
    fun `edited non-signature and still unknown models are untouched`() {
        val models = listOf(
            Model(modelId = "new-model", capabilitiesEdited = true),
            Model(modelId = "new-model", abilities = listOf(ModelAbility.REASONING)),
            Model(modelId = "new-model", inputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
            Model(modelId = "new-model", outputModalities = listOf(Modality.IMAGE)),
            Model(modelId = "new-model", inputModalities = emptyList()),
            Model(modelId = "unknown-model"),
        )
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = models)))
        assertSame(settings, settings.repairModelCapabilities(catalog))
    }

    @Test
    fun `known registry repairs even with empty catalog`() {
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(Model(modelId = "gpt-4o")))))
        assertEquals(listOf(ModelAbility.TOOL), settings.repairModelCapabilities(ModelCatalog.EMPTY).providers.single().models.single().abilities)
    }

    @Test
    fun `repair retains reasoning metadata ability rule without using discovery`() {
        val model = Model(
            modelId = "unknown-model", reasoningCapabilities = ReasoningCapabilities(),
            discoveredCapabilities = DiscoveredModelCapabilities(abilities = listOf(ModelAbility.TOOL)),
        )
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        val repaired = settings.repairModelCapabilities(ModelCatalog.EMPTY).providers.single().models.single()
        assertEquals(model.copy(abilities = listOf(ModelAbility.REASONING)), repaired)
    }
}
