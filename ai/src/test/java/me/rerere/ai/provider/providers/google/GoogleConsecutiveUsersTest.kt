package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoogleConsecutiveUsersTest {
    @Test fun consecutiveParticipantsMergeOnlyInRequestAndPreserveToolBoundaries() {
        val contents = Json.parseToJsonElement("""[
            {"role":"user","parts":[{"text":"[A] hello"}]},
            {"role":"user","parts":[{"text":"[owner] hello"}]},
            {"role":"model","parts":[{"functionCall":{"name":"test","args":{}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"test","response":{}}}]},
            {"role":"user","parts":[{"text":"[B] followup"}]}
        ]""").jsonArray
        val merged = mergeAdjacentGoogleUsers(contents)
        assertEquals(5, contents.size); assertEquals(3, merged.size)
        assertEquals(listOf("[A] hello", "[owner] hello"), merged.first().jsonObject.getValue("parts").jsonArray
            .map { it.jsonObject.getValue("text").jsonPrimitive.content })
        assertTrue(merged[1].jsonObject.getValue("parts").jsonArray.single().jsonObject.containsKey("functionCall"))
        assertTrue(merged[2].jsonObject.getValue("parts").jsonArray.first().jsonObject.containsKey("functionResponse"))
        assertEquals(2, merged[2].jsonObject.getValue("parts").jsonArray.size)
    }
}
