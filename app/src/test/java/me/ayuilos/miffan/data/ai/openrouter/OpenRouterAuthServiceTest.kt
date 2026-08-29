package me.ayuilos.miffan.data.ai.openrouter

import me.ayuilos.miffan.data.datastore.OPENROUTER_FREE_MODEL_ID
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAuthServiceTest {
    @Test
    fun `PKCE challenge matches RFC 7636 example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            openRouterPkceChallenge(verifier),
        )
    }

    @Test
    fun `authorization URL contains callback state and S256 challenge`() {
        val url = buildOpenRouterAuthorizationUrl(
            state = "state-123",
            pkce = OpenRouterPkce(verifier = "verifier", challenge = "challenge"),
        ).toHttpUrl()

        assertEquals("openrouter.ai", url.host)
        assertEquals("/auth", url.encodedPath)
        assertEquals("challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        val callback = requireNotNull(url.queryParameter("callback_url")).toHttpUrl()
        assertEquals("miffan.ayuilos.me", callback.host)
        assertEquals("state-123", callback.queryParameter("state"))
    }

    @Test
    fun `connection configures the built-in free model without duplicates`() {
        val connected = Settings()
            .withOpenRouterConnection("first-key")
            .withOpenRouterConnection("replacement-key")
        val providers = connected.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.id == OPENROUTER_PROVIDER_ID }

        assertEquals(1, providers.size)
        val provider = providers.single()
        assertEquals("replacement-key", provider.apiKey)
        assertEquals(OpenAIAuthType.API_KEY, provider.authType)
        assertFalse(provider.useResponseApi)
        assertEquals(1, provider.models.count { it.modelId == "openrouter/free" })
        assertEquals(OPENROUTER_FREE_MODEL_ID, provider.models.single { it.modelId == "openrouter/free" }.id)
        assertEquals(OPENROUTER_FREE_MODEL_ID, connected.chatModelId)
        assertFalse(connected.enableSuggestion)
        assertTrue(provider.enabled)
    }

    @Test
    fun `key exchange error includes OpenRouter message`() {
        assertEquals(
            "OpenRouter key exchange failed (HTTP 401): Invalid code",
            openRouterHttpError(401, """{"message":"Invalid code"}"""),
        )
    }
}
