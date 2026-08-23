package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ReasoningCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatCompletionsOpenRouterReasoningRequestTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun buildRequest(
        reasoningLevel: ReasoningLevel,
        reasoningCapabilities: ReasoningCapabilities?,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(
                modelId = "vendor/reasoner",
                abilities = listOf(ModelAbility.REASONING),
                reasoningCapabilities = reasoningCapabilities,
            ),
            reasoningLevel = reasoningLevel,
        )
        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1"),
            false,
        ) as JsonObject
    }

    @Test
    fun `model saved before metadata support does not disable OpenRouter reasoning`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.OFF,
            reasoningCapabilities = null,
        )

        assertNull(body["reasoning"])
    }

    @Test
    fun `mandatory model coerces off to automatic reasoning`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.OFF,
            reasoningCapabilities = ReasoningCapabilities(mandatory = true),
        )

        assertNull(body["reasoning"])
    }

    @Test
    fun `optional model can still disable reasoning`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.OFF,
            reasoningCapabilities = ReasoningCapabilities(mandatory = false),
        )

        val reasoning = body["reasoning"]?.jsonObject
        assertEquals("none", reasoning?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `unsupported stored effort falls back to provider default`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.XHIGH,
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = listOf("low", "medium", "high"),
                defaultEffort = "low",
            ),
        )

        val reasoning = body["reasoning"]?.jsonObject
        assertEquals("low", reasoning?.get("effort")?.jsonPrimitive?.content)
    }
}
