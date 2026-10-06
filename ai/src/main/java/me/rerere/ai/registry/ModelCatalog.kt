package me.rerere.ai.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

data class CatalogCapabilities(
    val abilities: List<ModelAbility>,
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
)

class ModelCatalog private constructor(
    private val models: Map<String, JsonObject>,
    private val aliases: Map<String, String>,
) {
    private val canonicalIndex = models.keys.groupBy(::normalize)
    private val capabilities = models.mapValues { (_, model) -> requireNotNull(parseCapabilities(model)) }

    fun lookup(modelId: String): CatalogCapabilities? {
        val key = normalize(modelId)
        // An ambiguous canonical key must not fall through to an alias.
        canonicalIndex[key]?.let { ids ->
            return ids.singleOrNull()?.let(capabilities::get)
        }
        return aliases[key]?.let(capabilities::get)
    }

    fun toSnapshot(fetchedAt: String): String = buildJsonObject {
        put("source", "models.dev")
        put("fetchedAt", fetchedAt)
        put("models", JsonObject(models))
        put("aliases", JsonObject(aliases.mapValues { JsonPrimitive(it.value) }))
    }.toString()

    companion object {
        val EMPTY = ModelCatalog(emptyMap(), emptyMap())

        fun parseSnapshot(json: String): ModelCatalog {
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: error("Expected a catalog snapshot object")
            val models = root["models"] as? JsonObject ?: error("Missing catalog models")
            val aliases = root["aliases"] as? JsonObject ?: error("Missing catalog aliases")
            return ModelCatalog(
                models = parseModels(models),
                aliases = aliases.mapNotNull { (id, target) ->
                    val value = target as? JsonPrimitive
                    value?.takeIf { it.isString }?.content?.let { normalize(id) to it }
                }.groupBy({ it.first }, { it.second })
                    .mapNotNull { (id, targets) -> targets.distinct().singleOrNull()?.let { id to it } }
                    .toMap(),
            )
        }

        fun parseModelsDev(json: String, aliasesFrom: ModelCatalog?): ModelCatalog {
            val root = Json.parseToJsonElement(json) as? JsonObject
                ?: error("Expected a models.dev object")
            return ModelCatalog(parseModels(root), aliasesFrom?.aliases.orEmpty())
        }

        private fun normalize(id: String): String = id.lowercase()
            .substringAfterLast('/')
            .substringBefore(':')
            .substringBefore('@')
            .replace('.', '-')
            .replace('_', '-')

        private fun parseModels(models: JsonObject): Map<String, JsonObject> = models.mapNotNull { (id, value) ->
            val model = value as? JsonObject ?: return@mapNotNull null
            if (parseCapabilities(model) == null) return@mapNotNull null
            id to buildJsonObject {
                put("tool_call", model.getValue("tool_call"))
                put("reasoning", model.getValue("reasoning"))
                val modalities = model.getValue("modalities") as JsonObject
                put("modalities", buildJsonObject {
                    put("input", modalities.getValue("input"))
                    put("output", modalities.getValue("output"))
                })
            }
        }.toMap()

        private fun parseCapabilities(model: JsonObject): CatalogCapabilities? {
            fun boolean(field: String): Boolean? = (model[field] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.booleanOrNull

            val tool = boolean("tool_call") ?: return null
            val reasoning = boolean("reasoning") ?: return null
            val modalities = model["modalities"] as? JsonObject ?: return null
            return CatalogCapabilities(
                abilities = buildList {
                    if (tool) add(ModelAbility.TOOL)
                    if (reasoning) add(ModelAbility.REASONING)
                },
                inputModalities = parseModalities(modalities["input"] as? JsonArray ?: return null) ?: return null,
                outputModalities = parseModalities(modalities["output"] as? JsonArray ?: return null) ?: return null,
            )
        }

        private fun parseModalities(values: JsonArray): List<Modality>? {
            val names = values.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            }.toSet()
            return Modality.entries.filter { modality ->
                modality != Modality.FILE && modality.name.lowercase() in names
            }.ifEmpty { listOf(Modality.TEXT) }
        }
    }
}
