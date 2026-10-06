package me.ayuilos.miffan.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.utils.jsonPrimitiveOrNull

// 上游自营的 RikkaHub 搜索服务已移除，旧设置和备份中残留的条目必须在反序列化前剔除，
// 否则未知的多态类型会让整个搜索服务列表解码失败。
private val REMOVED_SEARCH_SERVICE_TYPES = setOf("rikkahub")

/**
 * 剔除已移除类型的搜索服务，并返回调整后的选中下标。
 * 选中项本身被剔除时回退到第一个服务。
 */
internal fun dropRemovedSearchServices(services: JsonArray, selected: Int): Pair<JsonArray, Int> {
    val removedIndices = services.indices.filter { index ->
        val type = (services[index] as? JsonObject)?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
        type in REMOVED_SEARCH_SERVICE_TYPES
    }
    if (removedIndices.isEmpty()) return services to selected

    val kept = JsonArray(services.filterIndexed { index, _ -> index !in removedIndices })
    val adjustedSelected = if (selected in removedIndices) 0 else selected - removedIndices.count { it < selected }
    return kept to adjustedSelected.coerceIn(0, (kept.size - 1).coerceAtLeast(0))
}
