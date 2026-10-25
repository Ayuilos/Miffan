package me.ayuilos.miffan.data.onboarding

import androidx.annotation.StringRes
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.openrouter.OPENROUTER_FREE_MODEL
import me.ayuilos.miffan.data.datastore.MAGPIE_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/** What the first-run conversation knows about a built-in service. Which model to use is the user's choice. */
data class OnboardingProviderHint(
    /** One line under the name in the service list. */
    @StringRes val description: Int,
    /** Where the user gets a key; null when the service has no such page. */
    val keyUrl: String? = null,
    /** The address differs per user, so it is asked for together with the key. */
    val needsAddress: Boolean = false,
)

private val HINTS: Map<Uuid, OnboardingProviderHint> = mapOf(
    OPENROUTER_PROVIDER_ID to OnboardingProviderHint(R.string.onboarding_provider_openrouter, "https://openrouter.ai/settings/keys"),
    Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e") to OnboardingProviderHint(R.string.onboarding_provider_openai, "https://platform.openai.com/api-keys"),
    Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d") to OnboardingProviderHint(R.string.onboarding_provider_gemini, "https://aistudio.google.com/apikey"),
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8") to OnboardingProviderHint(R.string.onboarding_provider_siliconflow, "https://cloud.siliconflow.cn/account/ak"),
    Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b") to OnboardingProviderHint(R.string.onboarding_provider_deepseek, "https://platform.deepseek.com/api_keys"),
    Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3") to OnboardingProviderHint(R.string.onboarding_provider_moonshot, "https://platform.moonshot.cn/console/api-keys"),
    MAGPIE_PROVIDER_ID to OnboardingProviderHint(R.string.onboarding_provider_magpie, needsAddress = true),
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88") to OnboardingProviderHint(R.string.onboarding_provider_gateway, "https://vercel.com/ai-gateway"),
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630") to OnboardingProviderHint(R.string.onboarding_provider_china_gateway, "https://www.tokenpony.cn/79clb"),
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c") to OnboardingProviderHint(R.string.onboarding_provider_bailian, "https://bailian.console.aliyun.com/?tab=model#/api-key"),
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191") to OnboardingProviderHint(R.string.onboarding_provider_volcengine, "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey"),
    Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be") to OnboardingProviderHint(R.string.onboarding_provider_zhipu, "https://open.bigmodel.cn/usercenter/apikeys"),
    Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c") to OnboardingProviderHint(R.string.onboarding_provider_stepfun, "https://platform.stepfun.com/interface-key"),
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7") to OnboardingProviderHint(R.string.onboarding_provider_gateway, "https://302.ai/"),
    Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5") to OnboardingProviderHint(R.string.onboarding_provider_hunyuan, "https://console.cloud.tencent.com/hunyuan/api-key"),
    Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990") to OnboardingProviderHint(R.string.onboarding_provider_xai, "https://console.x.ai/"),
    Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754") to OnboardingProviderHint(R.string.onboarding_provider_minimax, "https://platform.minimaxi.com/user-center/basic-information/interface-key"),
    Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637") to OnboardingProviderHint(R.string.onboarding_provider_mimo, "https://platform.xiaomimimo.com/"),
    Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8") to OnboardingProviderHint(R.string.onboarding_provider_gateway, "https://ackai.fun/"),
)

/** Services the user added themselves have no hint; they are listed with their address. */
fun onboardingHint(providerId: Uuid): OnboardingProviderHint? = HINTS[providerId]

/** The service list: OpenRouter first because it signs in with one tap, Magpie last because it runs at home. */
fun onboardingProviderOrder(providers: List<ProviderSetting>): List<ProviderSetting> =
    providers.sortedBy {
        when (it.id) {
            OPENROUTER_PROVIDER_ID -> 0
            MAGPIE_PROVIDER_ID -> 2
            else -> 1
        }
    }

/** The models a new user can chat with, by name: no recommendation, the user chooses. */
fun onboardingChatModels(models: List<Model>): List<Model> =
    models.filter { it.canChatInWords() }.distinctBy { it.modelId }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.ifBlank { it.modelId } })

/**
 * OpenRouter's choices after signing in: its free router first ([free], already connected), then the
 * other free models, then everything else by name.
 */
fun openRouterChatModels(listed: List<Model>, free: Model): List<Model> {
    val others = onboardingChatModels(listed).filterNot { it.modelId == free.modelId }
    val (freeOnes, paid) = others.partition { it.isFreeOnOpenRouter() }
    return listOf(free) + freeOnes + paid
}

/** OpenRouter marks free models with a `:free` suffix; its free router is free too. */
fun Model.isFreeOnOpenRouter(): Boolean = modelId == OPENROUTER_FREE_MODEL || modelId.endsWith(":free")

// Compatible services list every model as a chat model, so the rest are told apart by name.
private val NOT_CHAT_WORDS = listOf(
    "embed", "tts", "whisper", "transcribe", "realtime", "audio", "speech", "dall-e", "image", "moderation", "rerank", "ocr",
    "safety", "guard",
)

private fun Model.canChatInWords(): Boolean {
    if (type != ModelType.CHAT || Modality.TEXT !in outputModalities || Modality.IMAGE in outputModalities) return false
    val name = modelId.lowercase()
    return NOT_CHAT_WORDS.none { it in name }
}
