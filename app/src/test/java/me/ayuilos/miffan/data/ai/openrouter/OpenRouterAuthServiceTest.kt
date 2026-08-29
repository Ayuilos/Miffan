package me.ayuilos.miffan.data.ai.openrouter

import me.ayuilos.miffan.data.datastore.OPENROUTER_FREE_MODEL_ID
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
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
        val freeModel = provider.models.single { it.modelId == "openrouter/free" }
        assertEquals(OPENROUTER_FREE_MODEL_ID, freeModel.id)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), freeModel.abilities)
        assertEquals(OPENROUTER_FREE_MODEL_ID, connected.chatModelId)
        assertFalse(connected.enableSuggestion)
        assertTrue(provider.enabled)
    }

    @Test
    fun `saved key can restore free model after every model is removed`() {
        val connected = Settings().withOpenRouterConnection("saved-key")
        val withoutModels = connected.copy(
            providers = connected.providers.map { provider ->
                if (provider is ProviderSetting.OpenAI && provider.id == OPENROUTER_PROVIDER_ID) {
                    provider.copy(models = emptyList())
                } else {
                    provider
                }
            }
        )

        assertTrue(withoutModels.isNotConfigured())
        assertEquals("saved-key", withoutModels.openRouterApiKeyOrNull())

        val restored = withoutModels.withOpenRouterConnection(
            requireNotNull(withoutModels.openRouterApiKeyOrNull())
        )
        val provider = restored.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == OPENROUTER_PROVIDER_ID }
        assertEquals(listOf("openrouter/free"), provider.models.map { it.modelId })
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            provider.models.single().abilities,
        )
        assertEquals(OPENROUTER_FREE_MODEL_ID, restored.chatModelId)
    }

    @Test
    fun `saved key validation uses the authenticated current-key endpoint`() {
        val request = buildOpenRouterKeyValidationRequest("saved-key")

        assertEquals("https://openrouter.ai/api/v1/key", request.url.toString())
        assertEquals("Bearer saved-key", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
    }

    @Test
    fun `key validation accepts an unexpired key and records its expiry`() {
        val expiresAt = Instant.parse("2027-12-31T23:59:59Z")

        assertEquals(
            OpenRouterKeyValidationResult.Valid(expiresAt),
            parseOpenRouterKeyValidation(
                code = 200,
                body = """{"data":{"expires_at":"2027-12-31T23:59:59Z"}}""",
                now = Instant.parse("2027-01-01T00:00:00Z"),
            )
        )
    }

    @Test
    fun `key validation rejects expired and unauthorized keys`() {
        assertEquals(
            OpenRouterKeyValidationResult.Invalid,
            parseOpenRouterKeyValidation(
                code = 200,
                body = """{"data":{"expires_at":"2026-01-01T00:00:00Z"}}""",
                now = Instant.parse("2026-01-01T00:00:00Z"),
            )
        )
        assertEquals(
            OpenRouterKeyValidationResult.Invalid,
            parseOpenRouterKeyValidation(code = 401, body = ""),
        )
    }

    @Test
    fun `key validation keeps transient and malformed responses retryable`() {
        assertEquals(
            OpenRouterKeyValidationResult.Unavailable,
            parseOpenRouterKeyValidation(code = 500, body = ""),
        )
        assertEquals(
            OpenRouterKeyValidationResult.Unavailable,
            parseOpenRouterKeyValidation(code = 200, body = "not-json"),
        )
    }

    @Test
    fun `key exchange error includes OpenRouter message`() {
        assertEquals(
            "OpenRouter key exchange failed (HTTP 401): Invalid code",
            openRouterHttpError(401, """{"message":"Invalid code"}"""),
        )
    }
}
