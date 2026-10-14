package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionImageOptionsTest {
    @Test fun canDrawWithAcceptsExistingKindsAndEnabledSubscriptionChatModels() {
        val plain = Model(modelId = "plain")
        val subscription = Model(modelId = "subscription-chat")
        val disabled = Model(modelId = "disabled-chat")
        val embedding = Model(type = ModelType.EMBEDDING)
        val settings = Settings(providers = listOf(
            ProviderSetting.OpenAI(models = listOf(plain)),
            ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION, models = listOf(subscription, embedding)),
            ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION, enabled = false, models = listOf(disabled)),
        ))
        assertTrue(settings.canDrawWith(subscription))
        assertFalse(subscription.canOutputImage())
        assertFalse(settings.canDrawWith(plain))
        assertFalse(settings.canDrawWith(disabled))
        assertFalse(settings.canDrawWith(embedding))
        assertFalse(settings.canDrawWith(Model()))
        assertTrue(settings.canDrawWith(Model(outputModalities = listOf(Modality.IMAGE))))
        assertTrue(settings.canDrawWith(Model(type = ModelType.IMAGE)))
        assertTrue(settings.canDrawWith(Model(tools = setOf(BuiltInTools.ImageGeneration))))
        assertEquals(subscription, settings.copy(imageToolModelId = subscription.id).imageToolModelOrNull())
        assertNull(settings.copy(imageToolModelId = disabled.id).imageToolModelOrNull())
    }

    @Test fun eligibilityUsesProviderOverwriteIncludingEnabledState() {
        val override = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION)
        val chat = Model(providerOverwrite = override)
        fun settings(model: Model, auth: OpenAIAuthType = OpenAIAuthType.API_KEY) = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model), authType = auth)))
        assertTrue(settings(chat).canDrawWith(chat))
        val disabled = chat.copy(providerOverwrite = override.copy(enabled = false))
        assertFalse(settings(disabled).canDrawWith(disabled))
        val apiKey = chat.copy(providerOverwrite = ProviderSetting.OpenAI())
        assertFalse(settings(apiKey, OpenAIAuthType.CHATGPT_SUBSCRIPTION).canDrawWith(apiKey))
    }

    @Test fun optionsPreferMiniReuseExistingModelsAndSkipDisabledOrNonChatProviders() {
        val full = Model(modelId = "driver")
        val mini = Model(modelId = "driver-MINI")
        val first = ProviderSetting.OpenAI(name = "First subscription", authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            models = listOf(Model(type = ModelType.IMAGE), full, mini))
        val fallback = ProviderSetting.OpenAI(name = "Second subscription", authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            models = listOf(full.copy(), Model(modelId = "later")))
        val settings = Settings(providers = listOf(first, fallback, first.copy(enabled = false),
            ProviderSetting.OpenAI(models = listOf(mini)),
            first.copy(models = listOf(Model(type = ModelType.IMAGE), Model(type = ModelType.EMBEDDING)))))
        val options = settings.subscriptionImageOptions()
        assertEquals(2, options.size)
        assertEquals(SubscriptionImageOption(first.id, first.name, mini), options[0])
        assertEquals(fallback.models.first { it.type == ModelType.CHAT }.id, options[1].model.id)
        assertSame(mini, options[0].model)
        val selected = settings.copy(imageToolModelId = options[0].model.id)
        assertEquals(settings.providers, selected.providers)
        assertEquals(mini, selected.imageToolModelOrNull())
    }
}
