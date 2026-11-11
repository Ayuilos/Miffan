package me.ayuilos.miffan.data.appsettings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.MAGPIE_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AppSettingsTest {
    private val deepseekId = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")
    private val sentinels = (1..6).map { "sk-SENTINEL$it-7f3a9c2e81d04b6a5e7b0c21" }

    /** Every place a credential can sit in the settings, each holding its own sentinel. */
    private fun settingsFullOfSecrets(): Settings {
        val overwrite = ProviderSetting.OpenAI(name = "Override", apiKey = sentinels[1])
        val providers = DEFAULT_PROVIDERS.map { provider ->
            if (provider.id == deepseekId) (provider as ProviderSetting.OpenAI).copy(
                apiKey = sentinels[0],
                models = listOf(Model(modelId = "deepseek-chat", displayName = "DeepSeek V3",
                    customHeaders = listOf(CustomHeader("Authorization", "Bearer ${sentinels[2]}")), providerOverwrite = overwrite)),
            ) else provider
        } + listOf(
            ProviderSetting.OpenAI(name = "ChatGPT", authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                codexCredentials = OpenAICodexCredentials(accessToken = sentinels[3], refreshToken = sentinels[4],
                    accountId = "acct", email = "owner@example.com")),
            ProviderSetting.Google(name = "Vertex", vertexAI = true, useServiceAccount = true, privateKey = sentinels[5],
                serviceAccountEmail = "sa@example.iam.gserviceaccount.com"),
        )
        return Settings(providers = providers)
    }

    @Test
    fun theCatalogNeverHoldsACredentialOrAccountDetail() {
        val text = buildAppSettingsCatalog(settingsFullOfSecrets()).toString()
        sentinels.forEach { assertFalse("leaked $it", it in text) }
        // Nothing derived from a key either: no prefix or suffix of it.
        assertFalse("7f3a9c2e" in text)
        assertFalse("0c21" in text)
        assertFalse("owner@example.com" in text)
        assertFalse("sa@example" in text)
        assertFalse("Authorization" in text)
    }

    @Test
    fun theCatalogSaysWhetherAKeyIsThereAndWhereRequestsGo() {
        val providers = buildAppSettingsCatalog(settingsFullOfSecrets())["providers"]!!.jsonArray.associateBy {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        val deepseek = providers.getValue("DeepSeek").jsonObject
        assertEquals("set", deepseek["key"]!!.jsonPrimitive.content)
        assertEquals("api.deepseek.com", deepseek["host"]!!.jsonPrimitive.content)
        assertEquals("true", deepseek["preset"]!!.jsonPrimitive.content)
        assertEquals("unset", providers.getValue("OpenAI").jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("signed_in", providers.getValue("ChatGPT").jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("set", providers.getValue("Vertex").jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun originsShowTheBareHostOnlyForHttpsOnTheDefaultPort() {
        assertEquals("api.deepseek.com", displayOriginOf("https://api.deepseek.com/v1"))
        assertEquals("api.deepseek.com", displayOriginOf("https://API.deepseek.com:443/v1/"))
        assertEquals("https://example.com:8443", displayOriginOf("https://example.com:8443/v1"))
        assertEquals("http://192.168.1.5:3000", displayOriginOf("http://192.168.1.5:3000/v1"))
        assertNull(displayOriginOf("ftp://example.com"))
        assertNull(displayOriginOf("not a url"))
    }

    private fun custom(name: String = "My gateway", api: String = "openai", baseUrl: String) = buildJsonObject {
        putJsonObject("custom") {
            put("name", name)
            put("api", api)
            put("base_url", baseUrl)
        }
    }

    @Test
    fun aListedProviderIsConnectedByIdAndKeepsItsAddress() {
        val settings = Settings(providers = DEFAULT_PROVIDERS)
        val result = resolveConnectTarget(buildJsonObject { put("provider_id", deepseekId.toString()) }, settings)
        val target = (result as ConnectTargetResult.Ok).target as ConnectTarget.Existing
        assertEquals("DeepSeek", target.name)
        assertEquals("api.deepseek.com", target.origin)
        assertFalse(target.hasKey)
        assertEquals("https://platform.deepseek.com/api_keys", target.keyUrl)
    }

    @Test
    fun callsTheCardCannotShowAreRefusedWithAReason() {
        val settings = settingsFullOfSecrets()
        fun invalid(input: kotlinx.serialization.json.JsonObject) =
            assertTrue(input.toString(), resolveConnectTarget(input, settings) is ConnectTargetResult.Invalid)
        invalid(buildJsonObject { })
        invalid(buildJsonObject { put("provider_id", Uuid.random().toString()) })
        invalid(buildJsonObject { put("provider_id", "nope") })
        invalid(buildJsonObject { put("provider_id", MAGPIE_PROVIDER_ID.toString()) })
        settings.providers.filter { it.name == "ChatGPT" || it.name == "Vertex" }.forEach {
            invalid(buildJsonObject { put("provider_id", it.id.toString()) })
        }
        invalid(custom(baseUrl = "https://user:pass@example.com/v1"))
        invalid(custom(baseUrl = "https://example.com/v1?key=abc"))
        invalid(custom(baseUrl = "ftp://example.com"))
        invalid(custom(api = "cohere", baseUrl = "https://example.com/v1"))
        invalid(custom(name = "", baseUrl = "https://example.com/v1"))
        invalid(buildJsonObject {
            put("provider_id", deepseekId.toString())
            putJsonObject("custom") { put("name", "x"); put("api", "openai"); put("base_url", "https://example.com") }
        })
    }

    @Test
    fun aCustomServiceShowsItsFullOriginWhenNotPlainHttps() {
        val result = resolveConnectTarget(custom(baseUrl = "http://192.168.1.5:3000/v1/"), Settings(providers = DEFAULT_PROVIDERS))
        val target = (result as ConnectTargetResult.Ok).target as ConnectTarget.Custom
        assertEquals("http://192.168.1.5:3000", target.origin)
        assertEquals("http://192.168.1.5:3000/v1", target.baseUrl)
        assertEquals(CustomProviderApi.OPENAI, target.api)
    }

    @Test
    fun theKeyIsStoredOnlyForTheProviderTheCardShowed() {
        val settings = Settings(providers = DEFAULT_PROVIDERS)
        val target = (resolveConnectTarget(buildJsonObject { put("provider_id", deepseekId.toString()) }, settings)
            as ConnectTargetResult.Ok).target
        val saved = settings.withConnectedProvider(target, "  sk-new  ") as ConnectSave.Saved
        val deepseek = saved.settings.providers.first { it.id == deepseekId } as ProviderSetting.OpenAI
        assertEquals("sk-new", deepseek.apiKey)
        assertEquals("https://api.deepseek.com/v1", deepseek.baseUrl)
        assertTrue(deepseek.enabled)
        assertEquals(settings.providers.filter { it.id != deepseekId }, saved.settings.providers.filter { it.id != deepseekId })
    }

    @Test
    fun aProviderMovedOrRemovedAfterTheCardShowedGetsNoKey() {
        val settings = Settings(providers = DEFAULT_PROVIDERS)
        val target = (resolveConnectTarget(buildJsonObject { put("provider_id", deepseekId.toString()) }, settings)
            as ConnectTargetResult.Ok).target
        val moved = settings.copy(providers = settings.providers.map {
            if (it.id == deepseekId) (it as ProviderSetting.OpenAI).copy(baseUrl = "https://evil.example.com/v1") else it
        })
        assertEquals(ConnectSave.TargetChanged, moved.withConnectedProvider(target, "sk-new"))
        val removed = settings.copy(providers = settings.providers.filter { it.id != deepseekId })
        assertEquals(ConnectSave.TargetChanged, removed.withConnectedProvider(target, "sk-new"))
    }

    @Test
    fun aCustomServiceIsAddedWithItsKeyAndNoModels() {
        val settings = Settings(providers = DEFAULT_PROVIDERS)
        val target = (resolveConnectTarget(custom(api = "claude", baseUrl = "https://relay.example.com/v1"), settings)
            as ConnectTargetResult.Ok).target
        val saved = settings.withConnectedProvider(target, "sk-relay") as ConnectSave.Saved
        val added = saved.settings.providers.last() as ProviderSetting.Claude
        assertEquals(settings.providers.size + 1, saved.settings.providers.size)
        assertEquals("My gateway", added.name)
        assertEquals("https://relay.example.com/v1", added.baseUrl)
        assertEquals("sk-relay", added.apiKey)
        assertTrue(added.models.isEmpty())
    }

    @Test
    fun theResultThePartnerReadsHasModelsButNoKey() {
        val key = sentinels[0]
        val provider = ProviderSetting.OpenAI(name = "DeepSeek", apiKey = key)
        val models = (1..80).map { Model(modelId = "m$it", displayName = "Model $it") }
        val text = connectResultJson(provider, checked = true, models = models)
        assertFalse(key in text)
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("connected", json["status"]!!.jsonPrimitive.content)
        assertEquals("80", json["model_count"]!!.jsonPrimitive.content)
        assertEquals(CONNECT_RESULT_MODELS, json["models"]!!.jsonArray.size)
        val unchecked = connectResultJson(provider, checked = false, models = emptyList())
        assertFalse(key in unchecked)
        assertEquals("saved_unchecked", Json.parseToJsonElement(unchecked).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun serviceErrorsLoseTheKeyBeforeTheCardShowsThem() {
        val key = "sk-abcdef1234567890"
        val detail = scrubServiceDetail("Incorrect API key provided: $key. Also sk-other999999999 is wrong.", key)
        assertFalse(key in detail)
        assertFalse("sk-other" in detail)
        assertTrue("Incorrect API key provided" in detail)
    }

    @Test
    fun onlyTheAppMaySettleASecureCard() {
        val secret = setOf("card")
        // Approving or answering a card from anywhere else is refused; declining is not.
        assertNull(secretCardDecisionIds("card", emptySet(), secret, settles = true, fromCard = false))
        assertEquals(emptySet<String>(), secretCardDecisionIds("card", emptySet(), secret, settles = false, fromCard = false))
        // Allow-all on other calls never sweeps a card along.
        assertEquals(setOf("b"), secretCardDecisionIds("a", setOf("b", "card"), secret, settles = true, fromCard = false))
        assertEquals(setOf("b", "card"), secretCardDecisionIds("a", setOf("b", "card"), secret, settles = false, fromCard = false))
        // The app's own path settles exactly that card and nothing that is not a card.
        assertEquals(emptySet<String>(), secretCardDecisionIds("card", setOf("b"), secret, settles = true, fromCard = true))
        assertNull(secretCardDecisionIds("a", emptySet(), secret, settles = true, fromCard = true))
    }

    @Test
    fun keyLookingClipboardTextIsHiddenAndOrdinaryTextIsNot() {
        assertEquals("my key is $REDACTED_CREDENTIAL ok", redactCredentials("my key is sk-7f3a9c2e81d04b6a5e7b0c21 ok"))
        assertTrue(looksLikeCredential("AIzaSyD-1234567890abcdefghijklmnopqrstu"))
        assertTrue(looksLikeCredential("Bearer abcdefghijklmnop1234"))
        assertTrue(looksLikeCredential("Xk29fLm3Qp7Rt8Vw1Zy4Ab6Cd0Ef5Gh2Jk"))
        listOf(
            "Let's meet at 3pm tomorrow",
            "https://github.com/user/repo/commit/a5cc8b506f1e2d3c4b5a69788766554433221100",
            "a5cc8b506f1e2d3c4b5a69788766554433221100",
            "skeleton-key-for-the-door",
        ).forEach { assertFalse(it, looksLikeCredential(it)) }
    }
}
