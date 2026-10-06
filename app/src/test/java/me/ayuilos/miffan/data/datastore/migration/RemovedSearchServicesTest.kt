package me.ayuilos.miffan.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovedSearchServicesTest {
    private fun service(type: String) = buildJsonObject { put("type", type) }

    private fun types(services: JsonArray) = services.map { it.jsonObject["type"]!!.jsonPrimitive.content }

    @Test
    fun `drops rikkahub and shifts selection that came after it`() {
        val services = JsonArray(listOf(service("bing_local"), service("rikkahub"), service("tavily")))

        val (kept, selected) = dropRemovedSearchServices(services, selected = 2)

        assertEquals(listOf("bing_local", "tavily"), types(kept))
        assertEquals(1, selected)
    }

    @Test
    fun `falls back to first service when rikkahub was selected`() {
        val services = JsonArray(listOf(service("bing_local"), service("rikkahub")))

        val (kept, selected) = dropRemovedSearchServices(services, selected = 1)

        assertEquals(listOf("bing_local"), types(kept))
        assertEquals(0, selected)
    }

    @Test
    fun `leaves lists without removed services untouched`() {
        val services = JsonArray(listOf(service("bing_local"), service("exa")))

        assertEquals(services to 1, dropRemovedSearchServices(services, selected = 1))
    }

    @Test
    fun `settings json migration drops rikkahub search service from backups`() {
        val json = """
            {"searchServices":[{"type":"rikkahub","id":"a"},{"type":"exa","id":"b"}],"searchServiceSelected":1}
        """.trimIndent()

        val migrated = JsonInstant.parseToJsonElement(SettingsJsonMigrator.migrate(json)).jsonObject

        assertEquals(listOf("exa"), types(migrated["searchServices"]!!.jsonArray))
        assertEquals(0, migrated["searchServiceSelected"]!!.jsonPrimitive.int)
    }
}
