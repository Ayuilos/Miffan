package me.ayuilos.miffan.data.onboarding

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.DEFAULT_PROVIDERS
import me.ayuilos.miffan.data.datastore.MAGPIE_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.OnboardingMigration
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.InterfaceModeState
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class OnboardingTest {
    private fun chat(id: String) = Model(modelId = id, displayName = id)

    @Test
    fun theUserChoosesFromEveryTextChatModelListedByName() {
        val models = listOf(
            chat("gpt-5-mini"),
            Model(modelId = "claude-x", displayName = "Claude X"),
            chat("text-embedding-3-small"),
            chat("tts-1"),
            Model(modelId = "a-drawer", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
            Model(modelId = "a-vector", type = ModelType.EMBEDDING),
            chat("gpt-5-mini"),
        )
        // No recommendation: only what cannot chat in words is left out, and the rest is in name order.
        assertEquals(listOf("claude-x", "gpt-5-mini"), onboardingChatModels(models).map { it.modelId })
        assertTrue(onboardingChatModels(listOf(chat("text-embedding-3-small"))).isEmpty())
    }

    @Test
    fun openRouterPutsItsFreeRouterFirstThenTheOtherFreeModels() {
        val free = chat("openrouter/free")
        val listed = listOf(chat("z-ai/glm-4.6"), chat("deepseek/deepseek-chat:free"), chat("anthropic/claude-x"),
            chat("openrouter/free"), chat("meta/llama:free"))
        assertEquals(
            listOf("openrouter/free", "deepseek/deepseek-chat:free", "meta/llama:free", "anthropic/claude-x", "z-ai/glm-4.6"),
            openRouterChatModels(listed, free).map { it.modelId },
        )
        assertTrue(openRouterChatModels(listed, free).first() === free)
    }

    @Test
    fun serviceListPutsOneTapSignInFirstAndTheHomeGatewayLast() {
        val ordered = onboardingProviderOrder(DEFAULT_PROVIDERS)
        assertEquals(OPENROUTER_PROVIDER_ID, ordered.first().id)
        assertEquals(MAGPIE_PROVIDER_ID, ordered.last().id)
        assertEquals(DEFAULT_PROVIDERS.size, ordered.size)
        // Every built-in service has a line in the list.
        assertTrue(DEFAULT_PROVIDERS.all { onboardingHint(it.id) != null })
    }

    @Test
    fun usedInstallationsAndOlderBackupsNeverSeeThePartnerChoice() = runBlocking {
        assertFalse(initialOnboardingDone(0, hasSavedProviders = false))
        assertTrue(initialOnboardingDone(2, hasSavedProviders = false))
        assertTrue(initialOnboardingDone(0, hasSavedProviders = true))

        val migration = OnboardingMigration()
        val fresh = migration.migrate(emptyPreferences())
        assertEquals(false, fresh[SettingsStore.ONBOARDING_DONE])
        assertFalse(migration.shouldMigrate(fresh))
        assertEquals(true, migration.migrate(preferencesOf(SettingsStore.LAUNCH_COUNT to 3))[SettingsStore.ONBOARDING_DONE])

        assertTrue(JsonInstant.decodeFromString<Settings>("{}").onboardingDone)
    }

    @Test
    fun stageFollowsConnectionModeAndTheFlag() {
        val im = InterfaceModeState(mode = InterfaceMode.IM)
        val connected = DEFAULT_PROVIDERS.map { if (it.id == OPENROUTER_PROVIDER_ID) it.copyProvider(models = listOf(chat("m"))) else it }
        val fresh = Settings(providers = DEFAULT_PROVIDERS, interfaceMode = im, onboardingDone = false)
        assertEquals(OnboardingStage.CONNECT, fresh.onboardingStage())
        assertEquals(OnboardingStage.PICK_PARTNER, fresh.copy(providers = connected).onboardingStage())
        assertEquals(OnboardingStage.NONE, fresh.copy(providers = connected, onboardingDone = true).onboardingStage())
        // Professional mode has no partner choice; with nothing connected it still starts here.
        val pro = fresh.copy(interfaceMode = InterfaceModeState(mode = InterfaceMode.PROFESSIONAL))
        assertEquals(OnboardingStage.NONE, pro.copy(providers = connected).onboardingStage())
        assertEquals(OnboardingStage.CONNECT, pro.copy(onboardingDone = true).onboardingStage())
    }

    @Test
    fun connectingAddsTheModelsAndChatsWithThePickedOne() {
        val deepseek = DEFAULT_PROVIDERS.first { it.name == "DeepSeek" } as ProviderSetting.OpenAI
        val chatModel = chat("deepseek-chat")
        val fast = chat("deepseek-lite")
        val settings = Settings(providers = DEFAULT_PROVIDERS).withOnboardingConnection(deepseek.withApiKey("sk-1"), listOf(chatModel, fast), chatModel)
        val saved = settings.providers.first { it.id == deepseek.id } as ProviderSetting.OpenAI
        assertEquals("sk-1", saved.apiKey)
        assertTrue(saved.enabled)
        assertEquals(listOf("deepseek-chat", "deepseek-lite"), saved.models.map { it.modelId })
        assertEquals(chatModel.id, settings.chatModelId)
        assertEquals(DEFAULT_PROVIDERS.size, settings.providers.size)

        val typed = ProviderSetting.OpenAI(name = "My service", baseUrl = "http://127.0.0.1:1/v1", apiKey = "k")
        val added = Settings(providers = DEFAULT_PROVIDERS).withOnboardingConnection(typed, listOf(chatModel), chatModel)
        assertEquals(typed.id, added.providers.last().id)
    }

    @Test
    fun aConnectionCanBeTakenBackWithOrWithoutTheSettingsFromBefore() {
        val before = Settings(providers = DEFAULT_PROVIDERS)
        val deepseek = DEFAULT_PROVIDERS.first { it.name == "DeepSeek" }
        val model = chat("deepseek-chat")
        val connected = before.withOnboardingConnection(deepseek.withApiKey("sk-1"), listOf(model), model)

        assertEquals(before.providers, connected.undoOnboardingConnection(before).providers)
        assertEquals(before.chatModelId, connected.undoOnboardingConnection(before).chatModelId)

        // After a restart only the connected service is known: it loses its key and models.
        val cleared = connected.undoOnboardingConnection(null).providers.first { it.id == deepseek.id } as ProviderSetting.OpenAI
        assertEquals("", cleared.apiKey)
        assertTrue(cleared.models.isEmpty())
        assertTrue(connected.undoOnboardingConnection(null).isNotConfigured())

        // A service typed in by hand is removed again.
        val typed = ProviderSetting.OpenAI(name = "Mine", baseUrl = "http://127.0.0.1:1/v1", apiKey = "k")
        val custom = before.withOnboardingConnection(typed, listOf(model), model)
        assertTrue(custom.undoOnboardingConnection(null).providers.none { it.id == typed.id })
    }

    @Test
    fun failuresAreToldApartByWhatTheServiceSaid() = runBlocking {
        val client = OkHttpClient()
        withServer(
            "/bad/models" to (401 to """{"error":{"message":"Incorrect API key provided"}}"""),
            "/down/models" to (503 to """{"error":{"message":"upstream overloaded"}}"""),
            "/empty/models" to (200 to """{"data":[]}"""),
            "/gemini/models" to (400 to """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""),
        ) { base ->
            suspend fun openAI(path: String) = runCatching {
                OpenAIProvider(client).listModels(ProviderSetting.OpenAI(baseUrl = "$base/$path", apiKey = "k"))
            }
            assertEquals(ConnectResult.KeyRejected, classifyConnectFailure(openAI("bad").exceptionOrNull()!!))
            assertEquals(ConnectResult.ServiceError(503, "upstream overloaded"), classifyConnectFailure(openAI("down").exceptionOrNull()!!))
            assertEquals(emptyList<Model>(), openAI("empty").getOrThrow())
            val gemini = runCatching { GoogleProvider(client).listModels(ProviderSetting.Google(baseUrl = "$base/gemini", apiKey = "k")) }
            assertEquals(ConnectResult.KeyRejected, classifyConnectFailure(gemini.exceptionOrNull()!!))
        }
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val refused = runCatching {
            OpenAIProvider(client).listModels(ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:$closedPort/v1", apiKey = "k"))
        }
        assertEquals(ConnectResult.Unreachable, classifyConnectFailure(refused.exceptionOrNull()!!))
        assertEquals(ConnectResult.Unreachable, classifyConnectFailure(IllegalArgumentException("Expected URL scheme 'http' or 'https'")))
    }

    private suspend fun withServer(vararg routes: Pair<String, Pair<Int, String>>, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        routes.forEach { (path, response) ->
            server.createContext(path) { exchange ->
                val body = response.second.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(response.first, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
