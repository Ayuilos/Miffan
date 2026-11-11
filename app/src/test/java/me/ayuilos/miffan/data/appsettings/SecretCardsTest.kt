package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.ai.mcp.McpCommonOptions
import me.ayuilos.miffan.data.ai.mcp.McpOAuthState
import me.ayuilos.miffan.data.ai.mcp.McpServerConfig
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.transformMcpSecrets
import me.ayuilos.miffan.data.datastore.transformSearchServiceSecrets
import me.ayuilos.miffan.data.revision.aiServicesSnapshot
import me.ayuilos.miffan.data.revision.restoreAiServices
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecretCardsTest {
    private val tavilyKey = "tvly-SENTINEL-7f3a9c2e81d04b6a5e7b"
    private val mcpToken = "Bearer mcp-SENTINEL-0a1b2c3d4e5f60718293"
    private val bing = SearchServiceOptions.BingLocalOptions()
    private val tavily = SearchServiceOptions.TavilyOptions(apiKey = tavilyKey)
    private val searx = SearchServiceOptions.SearXNGOptions(url = "https://searx.example.com", password = "searx-pass-SENTINEL")
    private val mcp = McpServerConfig.StreamableHTTPServer(url = "https://mcp.example.com/mcp",
        commonOptions = McpCommonOptions(name = "Notes", headers = listOf("Authorization" to mcpToken)))
    private val oauthMcp = McpServerConfig.SseTransportServer(url = "https://oauth.example.com/sse",
        commonOptions = McpCommonOptions(name = "Docs", oauth = McpOAuthState(enabled = true, accessToken = "oauth-SENTINEL")))
    private val model = Model(modelId = "m1", displayName = "Model One")
    private val provider = ProviderSetting.OpenAI(name = "Relay", baseUrl = "https://relay.example.com/v1", models = listOf(model))

    private fun settings() = Settings(
        searchServices = listOf(bing, tavily, searx),
        searchServiceSelected = 0,
        mcpServers = listOf(mcp, oauthMcp),
        providers = listOf(provider),
    )

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun ok(result: ConnectTargetResult) = (result as? ConnectTargetResult.Ok)?.target ?: error("expected Ok, got $result")

    private fun invalid(result: ConnectTargetResult) = (result as? ConnectTargetResult.Invalid)?.reason ?: error("expected Invalid, got $result")

    @Test
    fun searchKeysGoToAServiceWithAFixedAddress() {
        val existing = ok(resolveSearchTarget(json("""{"service_id":"${tavily.id}"}"""), settings())) as SearchTarget
        assertEquals(tavily.id, existing.serviceId)
        assertEquals("api.tavily.com", existing.origin)
        assertTrue(existing.hasKey)
        // Asking for a kind that is already there replaces that one's key rather than adding a twin.
        assertEquals(tavily.id, (ok(resolveSearchTarget(json("""{"type":"tavily"}"""), settings())) as SearchTarget).serviceId)
        val fresh = ok(resolveSearchTarget(json("""{"type":"exa"}"""), settings())) as SearchTarget
        assertNull(fresh.serviceId)
        assertEquals("api.exa.ai", fresh.origin)
        // The owner can edit where SearXNG sends requests, so a card could not promise an address.
        assertTrue("settings page" in invalid(resolveSearchTarget(json("""{"service_id":"${searx.id}"}"""), settings())))
        assertTrue("either" in invalid(resolveSearchTarget(json("""{}"""), settings())))
        assertTrue("type must be" in invalid(resolveSearchTarget(json("""{"type":"jina"}"""), settings())))
    }

    @Test
    fun savingASearchKeyReplacesOrAddsWithoutSwitchingAndNeverEchoesTheKey() {
        val replaced = settings().withSearchKey(ok(resolveSearchTarget(json("""{"service_id":"${tavily.id}"}"""), settings())) as SearchTarget,
            " tvly-new-key ", checked = true) as SecretSave.Saved
        assertEquals("tvly-new-key", replaced.settings.searchServices.first { it.id == tavily.id }.searchApiKey())
        assertEquals(0, replaced.settings.searchServiceSelected)
        assertFalse("tvly-new-key" in replaced.result)
        assertTrue("select_search_service" in replaced.result)

        val newId = Uuid.random()
        val added = settings().withSearchKey(ok(resolveSearchTarget(json("""{"type":"exa"}"""), settings())) as SearchTarget,
            "exa-key", checked = false, newId = newId) as SecretSave.Saved
        val exa = added.settings.searchServices.last()
        assertEquals(newId, exa.id)
        assertTrue(exa is SearchServiceOptions.ExaOptions && exa.apiKey == "exa-key")
        assertTrue("saved_unchecked" in added.result)

        val target = ok(resolveSearchTarget(json("""{"service_id":"${tavily.id}"}"""), settings())) as SearchTarget
        val removed = settings().copy(searchServices = listOf(SearchServiceOptions.BingLocalOptions()))
        assertEquals(SecretSave.TargetChanged, removed.withSearchKey(target, "k", checked = true))
    }

    @Test
    fun secretHeadersOnlyGoWhereTheCardSaid() {
        val target = ok(resolveHeaderTarget(json("""{"mcp_server_id":"${mcp.id}"}"""), settings())) as HeaderTarget
        assertEquals("Authorization", target.header)
        assertEquals("Bearer ", target.prefix)
        assertEquals("mcp.example.com", target.origin)
        val saved = settings().withSecretHeader(target, "new-token") as SecretSave.Saved
        val headers = saved.settings.mcpServers.first { it.id == mcp.id }.commonOptions.headers
        assertEquals(listOf("Authorization" to "Bearer new-token"), headers)
        assertFalse("new-token" in saved.result)

        val moved = settings().copy(mcpServers = listOf(mcp.copy(url = "https://evil.example.com/mcp"), oauthMcp))
        assertEquals(SecretSave.TargetChanged, moved.withSecretHeader(target, "new-token"))

        assertTrue("OAuth" in invalid(resolveHeaderTarget(json("""{"mcp_server_id":"${oauthMcp.id}"}"""), settings())))
        assertTrue("plain header" in invalid(resolveHeaderTarget(json("""{"mcp_server_id":"${mcp.id}","header":"Host"}"""), settings())))

        val raw = ok(resolveHeaderTarget(json("""{"provider_id":"${provider.id}","model_id":"m1","header":"X-Api-Key"}"""), settings())) as HeaderTarget
        assertEquals("", raw.prefix)
        val withModelHeader = settings().withSecretHeader(raw, "abc") as SecretSave.Saved
        assertEquals(listOf(CustomHeader("X-Api-Key", "abc")), withModelHeader.settings.providers.single().models.single().customHeaders)
        val overridden = settings().copy(providers = listOf(provider.copy(models = listOf(model.copy(providerOverwrite = ProviderSetting.OpenAI())))))
        assertTrue("settings page" in invalid(resolveHeaderTarget(json("""{"provider_id":"${provider.id}","model_id":"m1"}"""), overridden)))
    }

    @Test
    fun theCatalogNamesSearchServicesAndMcpHeadersButNoValue() {
        val text = buildAppSettingsCatalog(settings()).toString()
        listOf(tavilyKey, "SENTINEL", "searx-pass").forEach { assertFalse("leaked $it", it in text) }
        assertTrue("api.tavily.com" in text)
        assertTrue("mcp.example.com" in text)
    }

    @Test
    fun theSearchServiceInUseCanBeSwitchedAndUndone() {
        val change = json("""{"changes":[{"type":"select_search_service","service_id":"${tavily.id}"}]}""")
        val plan = planAppSettingsChange(change, settings()) as AppSettingsPlan.Ok
        assertEquals(1, plan.settings.searchServiceSelected)
        assertEquals(listOf(AppSettingsLine.SearchSelected("Tavily")), plan.lines)
        val restored = plan.settings.restoreAiServices(aiServicesSnapshot(settings()))
        assertEquals(0, restored.searchServiceSelected)
        // A service without its key cannot be switched to.
        val keyless = settings().copy(searchServices = listOf(SearchServiceOptions.BingLocalOptions(), tavily.copy(apiKey = "")))
        assertTrue(planAppSettingsChange(change, keyless) is AppSettingsPlan.Invalid)
    }

    @Test
    fun searchKeysMcpHeadersAndTokensAreKeptLikeProviderKeys() {
        val lock: (String) -> String = { if (it.isBlank()) it else "enc:$it" }
        val search = settings().searchServices.transformSearchServiceSecrets(lock)
        assertEquals("enc:$tavilyKey", search[1].searchApiKey())
        assertEquals("enc:searx-pass-SENTINEL", (search[2] as SearchServiceOptions.SearXNGOptions).password)
        val servers = settings().mcpServers.transformMcpSecrets(lock)
        assertEquals("Authorization" to "enc:$mcpToken", servers[0].commonOptions.headers.single())
        assertEquals("enc:oauth-SENTINEL", servers[1].commonOptions.oauth?.accessToken)
    }

    @Test
    fun everySecureCardToolIsGuarded() {
        assertEquals(SECRET_CARD_TOOLS, setOf(CONNECT_PROVIDER_TOOL, CONNECT_SEARCH_TOOL, SET_SECRET_HEADER_TOOL))
        // Every secure card tool is covered by the guard that only lets the card itself settle the call.
        assertNull(secretCardDecisionIds("c1", setOf("c1"), setOf("c1"), settles = true, fromCard = false))
    }
}
