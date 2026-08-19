package me.ayuilos.miffan.data.ai.tools.local

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalToolOptionTest {
    @Test
    fun `extension management is disabled by default`() {
        assertFalse(Assistant().localTools.contains(LocalToolOption.ExtensionManagement))
    }

    @Test
    fun `extension management round trips with stable discriminator`() {
        val original: LocalToolOption = LocalToolOption.ExtensionManagement

        val encoded = JsonInstant.encodeToString<LocalToolOption>(original)
        val discriminator = JsonInstant.parseToJsonElement(encoded)
            .jsonObject["type"]
            ?.jsonPrimitive
            ?.content
        val decoded = JsonInstant.decodeFromString<LocalToolOption>(encoded)

        assertEquals("extension_management", discriminator)
        assertEquals(original, decoded)
    }
}
