package me.ayuilos.miffan.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.ayuilos.miffan.utils.jsonPrimitiveOrNull
import me.ayuilos.miffan.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.ayuilos.miffan.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V4: namespace 独立化后，将旧备份中的全限定 Avatar 类型名改为稳定标识。
            // 同时迁移首个 Miffan 版本写出的新 namespace 类名，避免以后再次改包名时失效。
            JsonInstant.encodeToString(migratePersistedTypeNames(JsonObject(root)))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }
}

private val persistedTypeNameMapping = mapOf(
    "me.rerere.rikkahub.data.model.Avatar.Dummy" to "dummy",
    "me.rerere.rikkahub.data.model.Avatar.Emoji" to "emoji",
    "me.rerere.rikkahub.data.model.Avatar.Image" to "image",
    "me.rerere.rikkahub.data.model.Avatar.Miffan" to "miffan",
    "me.ayuilos.miffan.data.model.Avatar.Dummy" to "dummy",
    "me.ayuilos.miffan.data.model.Avatar.Emoji" to "emoji",
    "me.ayuilos.miffan.data.model.Avatar.Image" to "image",
    "me.ayuilos.miffan.data.model.Avatar.Miffan" to "miffan",
)

private fun migratePersistedTypeNames(element: JsonElement): JsonElement = when (element) {
    is JsonArray -> JsonArray(element.map(::migratePersistedTypeNames))
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            val typeName = if (key == "type") value.jsonPrimitiveOrNull?.contentOrNull else null
            persistedTypeNameMapping[typeName]?.let(::JsonPrimitive)
                ?: migratePersistedTypeNames(value)
        }
    )
    else -> element
}
