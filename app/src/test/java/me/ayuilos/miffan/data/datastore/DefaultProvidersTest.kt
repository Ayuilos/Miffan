package me.ayuilos.miffan.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers no longer add the RikkaHub service`() {
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "RikkaHub" })
    }

    @Test
    fun `default providers no longer include upstream sponsors`() {
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "AiHubMix" })
        assertFalse(DEFAULT_PROVIDERS.any { it.name == "随想AI网关" })
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `magpie starts off as a built-in OpenAI gateway on a local network address`() {
        val provider = DEFAULT_PROVIDERS.single { it.id == MAGPIE_PROVIDER_ID }

        assertTrue(provider is ProviderSetting.OpenAI)
        provider as ProviderSetting.OpenAI
        assertEquals("Magpie", provider.name)
        assertEquals("http://192.168.1.20:3425/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
    }
}
