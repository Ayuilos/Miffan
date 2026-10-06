package me.rerere.ai.registry

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.DiscoveredModelCapabilities
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ReasoningCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val capable = """{"tool_call":true,"reasoning":true,"modalities":{"input":["image","pdf","text"],"output":["text"]}}"""
    private val basic = """{"tool_call":false,"reasoning":false,"modalities":{"input":["text"],"output":["text"]}}"""

    @Test
    fun `normalization handles gateways punctuation regions and free suffixes`() {
        val catalog = ModelCatalog.parseModelsDev(
            """{"openai/gpt-6.1-sol":$capable,"openai/gpt-6-luna":$capable,"qwen/qwen3.6-plus":$capable,"anthropic/claude-opus-4-8":$capable}""",
            null,
        )
        listOf(
            "codex/gpt-6.1-sol", "openai/gpt-6.1-sol", "GPT_6.1_SOL", "gpt-6-luna@eu",
            "qwen/qwen3.6-plus:free", "anthropic/claude-opus-4.8", "gpt-6-luna@eu:free",
        ).forEach { id ->
            assertEquals(id, listOf(ModelAbility.TOOL, ModelAbility.REASONING), catalog.lookup(id)?.abilities)
        }
        assertNull(catalog.lookup("gpt-6.1-sol-preview"))
        assertNull(catalog.lookup("gpt-6.1"))
    }

    @Test
    fun `canonical wins over stale alias and ambiguous canonical never falls through`() {
        val catalog = ModelCatalog.parseSnapshot(
            """{"models":{"deepseek/deepseek-v4-flash":$basic,"deepseek/deepseek-v4.1-flash":$capable,"lab/shared.1":$basic,"other/shared-1":$capable},"aliases":{"deepseek-v4-flash":"deepseek/deepseek-v4.1-flash","shared-1":"deepseek/deepseek-v4.1-flash","latest":"deepseek/deepseek-v4.1-flash"}}""",
        )
        assertEquals(emptyList<ModelAbility>(), catalog.lookup("deepseek-v4-flash")?.abilities)
        assertNull(catalog.lookup("shared-1"))
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), catalog.lookup("latest")?.abilities)
    }

    @Test
    fun `pdf is dropped modalities are ordered and empty lists default to text`() {
        val catalog = ModelCatalog.parseModelsDev(
            """{"one":$capable,"two":{"tool_call":false,"reasoning":true,"modalities":{"input":["pdf"],"output":["video","audio","image","text","pdf","image"]}},"three":{"tool_call":true,"reasoning":false,"modalities":{"input":[],"output":[]}}}""",
            null,
        )
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), catalog.lookup("one")?.inputModalities)
        assertEquals(listOf(Modality.TEXT), catalog.lookup("two")?.inputModalities)
        assertEquals(Modality.entries.filter { it != Modality.FILE }, catalog.lookup("two")?.outputModalities)
        assertEquals(listOf(ModelAbility.REASONING), catalog.lookup("two")?.abilities)
        assertEquals(listOf(ModelAbility.TOOL), catalog.lookup("three")?.abilities)
        assertEquals(listOf(Modality.TEXT), catalog.lookup("three")?.outputModalities)
    }

    @Test
    fun `malformed entries are skipped without losing good entries`() {
        val catalog = ModelCatalog.parseModelsDev(
            """{"good":$capable,"missing":{},"array":[],"badBoolean":{"tool_call":"true","reasoning":false,"modalities":{"input":[],"output":[]}},"badModalities":{"tool_call":true,"reasoning":false,"modalities":{"input":[1],"output":[]}}}""",
            null,
        )
        listOf("missing", "array", "badBoolean", "badModalities").forEach { assertNull(catalog.lookup(it)) }
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), catalog.lookup("good")?.abilities)
    }

    @Test
    fun `refresh keeps snapshot aliases and round trips compact fields`() {
        val snapshot = ModelCatalog.parseSnapshot(
            """{"models":{"lab/new":$basic},"aliases":{"gateway":"lab/new"}}""",
        )
        val refreshed = ModelCatalog.parseModelsDev("""{"lab/new":$capable}""", snapshot)
        val encoded = refreshed.toSnapshot("2026-10-06T00:00:00Z")
        assertEquals(refreshed.lookup("new"), refreshed.lookup("gateway"))
        assertEquals(refreshed.lookup("gateway"), ModelCatalog.parseSnapshot(encoded).lookup("gateway"))
        assertTrue(encoded.contains("\"source\":\"models.dev\""))
        assertNull(ModelCatalog.parseModelsDev("{}", refreshed).lookup("gateway"))
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid snapshot structure throws for caller fallback`() {
        ModelCatalog.parseSnapshot("{}")
    }

    @Test
    fun `discovery wins per field then known registry then catalog then defaults`() {
        val catalog = ModelCatalog.parseModelsDev("""{"gpt-4o":$basic,"qwen-mt":$capable,"catalog-only":$capable}""", null)
        val known = ModelRegistry.resolveCapabilities(Model(modelId = "gpt-4o"), catalog)
        assertTrue(ModelRegistry.isKnown("gpt-4o"))
        assertEquals(listOf(ModelAbility.TOOL), known.abilities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), known.inputModalities)
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.resolveCapabilities(Model(modelId = "qwen-mt"), catalog).abilities)

        val discovered = ModelRegistry.resolveCapabilities(
            Model(modelId = "gpt-4o", discoveredCapabilities = DiscoveredModelCapabilities(
                abilities = emptyList(), outputModalities = listOf(Modality.AUDIO),
            )),
            catalog,
        )
        assertEquals(emptyList<ModelAbility>(), discovered.abilities)
        assertEquals(known.inputModalities, discovered.inputModalities)
        assertEquals(listOf(Modality.AUDIO), discovered.outputModalities)
        assertNull(discovered.discoveredCapabilities)

        val fallback = ModelRegistry.resolveCapabilities(Model(modelId = "catalog-only"), catalog)
        assertFalse(ModelRegistry.isKnown("catalog-only"))
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), fallback.abilities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), fallback.inputModalities)
        val partial = ModelRegistry.resolveCapabilities(
            Model(modelId = "catalog-only", discoveredCapabilities = DiscoveredModelCapabilities(inputModalities = listOf(Modality.VIDEO))),
            catalog,
        )
        assertEquals(listOf(Modality.VIDEO), partial.inputModalities)
        assertEquals(fallback.abilities, partial.abilities)
        val unknown = ModelRegistry.resolveCapabilities(Model(modelId = "unknown"), catalog)
        assertEquals(emptyList<ModelAbility>(), unknown.abilities)
        assertEquals(listOf(Modality.TEXT), unknown.inputModalities)
        assertEquals(listOf(Modality.TEXT), unknown.outputModalities)
        assertEquals(ModelRegistry.inferCapabilities("catalog-only", catalog).abilities, fallback.abilities)
    }

    @Test
    fun `reasoning metadata still adds reasoning and edited flag is backward compatible`() {
        val resolved = ModelRegistry.resolveCapabilities(Model(
            modelId = "unknown", reasoningCapabilities = ReasoningCapabilities(),
            discoveredCapabilities = DiscoveredModelCapabilities(abilities = emptyList()),
        ))
        assertEquals(listOf(ModelAbility.REASONING), resolved.abilities)
        val old = Json.decodeFromString<Model>("""{"modelId":"old"}""")
        assertFalse(old.capabilitiesEdited)
        val edited = old.copy(capabilitiesEdited = true)
        assertTrue(Json.decodeFromString<Model>(Json.encodeToString(edited)).capabilitiesEdited)
    }
}
