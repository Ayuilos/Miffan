package me.ayuilos.miffan.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CherryStudioProviderImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `import uses known registry then catalog then defaults`() {
        val models = listOf("gpt-4o", "gateway/new-model", "unknown").map { id ->
            buildJsonObject {
                put("id", id)
                put("name", "Name: $id")
            }
        }
        val llm = buildJsonObject {
            put("providers", JsonArray(listOf(buildJsonObject {
                put("type", "openai")
                put("apiKey", "test-key")
                put("models", JsonArray(models))
            })))
        }
        val persisted = buildJsonObject { put("llm", JsonPrimitive(llm.toString())) }
        val data = buildJsonObject {
            put("localStorage", buildJsonObject { put("persist:cherry-studio", persisted.toString()) })
        }
        val file = temporaryFolder.newFile("cherry.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(data.toString().toByteArray())
            zip.closeEntry()
        }
        val catalog = ModelCatalog.parseModelsDev(
            """{"openai/gpt-4o":{"tool_call":false,"reasoning":false,"modalities":{"input":["text"],"output":["text"]}},"lab/new-model":{"tool_call":true,"reasoning":true,"modalities":{"input":["image","text","pdf"],"output":["text"]}}}""",
            null,
        )
        val imported = CherryStudioProviderImporter.importProviders(file, catalog).single().models
        assertEquals(listOf(ModelAbility.TOOL), imported[0].abilities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), imported[0].inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), imported[1].abilities)
        assertEquals("Name: gateway/new-model", imported[1].displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), imported[1].inputModalities)
        assertEquals(emptyList<ModelAbility>(), imported[2].abilities)
        assertEquals(listOf(Modality.TEXT), imported[2].inputModalities)
    }
}
