package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

/**
 * Provider 模型列表接口返回的能力元数据。
 *
 * 各字段可独立缺省：Provider 明确返回的字段优先使用，缺省字段再交给本地 ModelRegistry 推断。
 * 该信息只参与“发现并添加模型”的过程，不持久化到设置中。
 */
data class DiscoveredModelCapabilities(
    val inputModalities: List<Modality>? = null,
    val outputModalities: List<Modality>? = null,
    val abilities: List<ModelAbility>? = null,
)

@Serializable
data class ReasoningCapabilities(
    val supportedEfforts: List<String>? = null,
    val defaultEffort: String? = null,
    val defaultEnabled: Boolean? = null,
    val supportsMaxTokens: Boolean? = null,
    val mandatory: Boolean = false,
)

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val reasoningCapabilities: ReasoningCapabilities? = null,
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    @Transient
    val discoveredCapabilities: DiscoveredModelCapabilities? = null,
) {
    fun supportedReasoningLevels(): List<ReasoningLevel> {
        val capabilities = reasoningCapabilities ?: return ReasoningLevel.entries
        val supportedEfforts = capabilities.supportedEfforts
        return ReasoningLevel.entries.filter { level ->
            when (level) {
                ReasoningLevel.OFF -> !capabilities.mandatory
                ReasoningLevel.AUTO -> true
                else -> supportedEfforts == null || level.effort in supportedEfforts
            }
        }.ifEmpty { listOf(ReasoningLevel.AUTO) }
    }

    fun resolveReasoningLevel(requested: ReasoningLevel): ReasoningLevel {
        val supportedLevels = supportedReasoningLevels()
        if (requested in supportedLevels) return requested

        val defaultLevel = reasoningCapabilities?.defaultEffort?.let { effort ->
            ReasoningLevel.entries.firstOrNull { it.effort == effort }
        }
        return defaultLevel?.takeIf { it in supportedLevels } ?: ReasoningLevel.AUTO
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}

