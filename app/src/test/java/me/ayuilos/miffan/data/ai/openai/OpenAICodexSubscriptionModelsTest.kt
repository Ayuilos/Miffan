package me.ayuilos.miffan.data.ai.openai

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ayuilos.miffan.data.ai.tools.subscriptionImageOptions
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.rerere.ai.provider.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAICodexSubscriptionModelsTest {
    private fun store(state: MutableStateFlow<Settings>) = mockk<SettingsStore>().also { store ->
        every { store.settingsFlow } returns state
        coEvery { store.update(any<(Settings) -> Settings>()) } coAnswers {
            state.value = firstArg<(Settings) -> Settings>()(state.value)
        }
    }

    private fun response(request: Request, body: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Response")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun signInPersistsOneDiscoveredChatModelBeforeReturningWithoutSelectingImageTool() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val provider = ProviderSetting.OpenAI()
            val state = MutableStateFlow(Settings(providers = listOf(provider)))
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val body = when (request.url.encodedPath) {
                    "/api/accounts/deviceauth/usercode" -> """{"device_auth_id":"test-device","user_code":"ABCD-EFGH","interval":"1"}"""
                    "/api/accounts/deviceauth/token" -> """{"authorization_code":"test-code","code_verifier":"test-verifier"}"""
                    "/oauth/token" -> """{"access_token":"test-access","refresh_token":"test-refresh","account_id":"test-account","expires_in":3600}"""
                    "/backend-api/codex/models" -> """{"models":[
                        {"slug":"driver-full","display_name":"Driver Full"},
                        {"slug":"driver-mini","display_name":"Driver Mini"}
                    ]}"""
                    else -> error("Unexpected request: ${request.url.encodedPath}")
                }
                response(request, body)
            }.build()
            var codeWasShown = false
            OpenAICodexAuthService(client, store(state)).signIn(provider.id) { codeWasShown = true }
            assertTrue(codeWasShown)
            val stored = state.value.providers.single() as ProviderSetting.OpenAI
            assertEquals(OpenAIAuthType.CHATGPT_SUBSCRIPTION, stored.authType)
            assertTrue(stored.useResponseApi)
            assertEquals("driver-mini", stored.models.single().modelId)
            assertEquals(ModelType.CHAT, stored.models.single().type)
            assertNull(stored.models.single().discoveredCapabilities)
            assertEquals(stored.models.single(), state.value.subscriptionImageOptions().single().model)
            assertNull(state.value.imageToolModelId)
            assertEquals("Bearer test-access", requests.last().header("Authorization"))
            assertNull(requests.last().header("X-Magpie-Drawers"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun existingChatModelsAreReusedWithoutFetchingOrDuplicating() = runTest {
        val model = Model(modelId = "saved-chat")
        val provider = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION, models = listOf(model))
        val state = MutableStateFlow(Settings(providers = listOf(provider)))
        val client = OkHttpClient.Builder().addInterceptor { error("Existing chat models must be reused") }.build()
        OpenAICodexAuthService(client, store(state)).ensureSubscriptionChatModel(provider.id)
        assertEquals(listOf(model), state.value.providers.single().models)
        assertSame(model, state.value.subscriptionImageOptions().single().model)
    }

    @Test fun catalogFailureKeepsSuccessfulLoginAndExistingNonChatModels() = runTest {
        val image = Model(modelId = "image", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
            baseUrl = OPENAI_CODEX_BASE_URL, models = listOf(image),
            codexCredentials = OpenAICodexCredentials("test-access", "test-refresh", "test-account"))
        val state = MutableStateFlow(Settings(providers = listOf(provider)))
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), "{}", 503) }.build()
        OpenAICodexAuthService(client, store(state)).ensureSubscriptionChatModel(provider.id)
        assertEquals(provider, state.value.providers.single())
        assertTrue(state.value.subscriptionImageOptions().isEmpty())
    }
}
