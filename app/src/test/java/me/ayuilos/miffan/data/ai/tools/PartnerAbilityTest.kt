package me.ayuilos.miffan.data.ai.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.local.DEFAULT_LOCAL_TOOLS
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.withAskUser
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.model.withInterfaceMode
import me.ayuilos.miffan.service.abilitiesTurnedOnSince
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PartnerAbilityTest {
    private val partner = Assistant(name = "Miffan")
    private val trigger = MessageRef(Uuid.random(), Uuid.random())

    @Test
    fun newPartnersCanAskQuestionsAndPartnersMadeBeforeGetItOnce() {
        assertTrue(LocalToolOption.AskUser in Assistant(name = "new").localTools)
        val old = listOf(partner.copy(localTools = listOf(LocalToolOption.TimeInfo)),
            partner.copy(id = Uuid.random(), localTools = listOf(LocalToolOption.Calendar)))
        val migrated = JsonInstant.decodeFromString<List<Assistant>>(withAskUser(JsonInstant.encodeToString(old)))
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser), migrated[0].localTools)
        assertEquals(listOf(LocalToolOption.Calendar, LocalToolOption.AskUser), migrated[1].localTools)
        val once = JsonInstant.encodeToString(migrated)
        assertEquals(once, withAskUser(once))
        assertEquals("not json", withAskUser("not json"))
    }

    @Test
    fun onlyAbilitiesStillOffCanBeRequested() {
        val store = mockk<SettingsStore>()
        val tools = buildSelfConfigTools(partner.id, store, trigger, "", false, localTools = DEFAULT_LOCAL_TOOLS)
        val ability = tools.single { it.name == REQUEST_ABILITY }
        assertTrue("- calendar:" in ability.description)
        assertFalse("- ask_user:" in ability.description)
        // Nothing left to ask for: no tool at all; without the partner's tools (other callers): none either.
        val everything = PartnerAbility.entries.map { it.option } + LocalToolOption.TimeInfo
        assertTrue(buildSelfConfigTools(partner.id, store, trigger, "", false, localTools = everything).none { it.name == REQUEST_ABILITY })
        assertTrue(buildSelfConfigTools(partner.id, store, trigger, "", false).none { it.name == REQUEST_ABILITY })
    }

    @Test
    fun anApprovedRequestTurnsTheAbilityOnAndItsToolsArriveMidTurn() = runTest {
        var settings = Settings(assistants = listOf(partner))
        val store = mockk<SettingsStore>()
        coEvery { store.update(any<(Settings) -> Settings>()) } answers { settings = firstArg<(Settings) -> Settings>()(settings) }
        val tool = buildSelfConfigTools(partner.id, store, trigger, "", false, localTools = partner.localTools)
            .single { it.name == REQUEST_ABILITY }
        assertTrue(tool.needsApproval(Json.parseToJsonElement("{}")))
        val result = tool.execute(Json.parseToJsonElement("""{"ability":"calendar","reason":"记下牙医预约"}""")).toString()
        assertTrue(result.contains("success"))
        assertEquals(DEFAULT_LOCAL_TOOLS + LocalToolOption.Calendar, settings.assistants.single().localTools)
        assertEquals(listOf(LocalToolOption.Calendar), abilitiesTurnedOnSince(partner, settings))
    }

    @Test
    fun onlyTheOwnerCanTurnAnAbilityOn() = runTest {
        val store = mockk<SettingsStore>()
        val tool = buildSelfConfigTools(partner.id, store, null, "", false, localTools = partner.localTools)
            .single { it.name == REQUEST_ABILITY }
        assertTrue(tool.execute(Json.parseToJsonElement("""{"ability":"calendar","reason":"x"}""")).toString().contains("Rejected"))
    }

    @Test
    fun thePartnerSeesWhatItCanAskForAndItsChatAbilities() {
        val settings = Settings(assistants = listOf(partner)).withInterfaceMode(InterfaceMode.IM)
        val environment = buildMyEnvironment(AgentAccess.forAssistant(partner, true), settings, Model(modelId = "m"), "P",
            setOf(REQUEST_ABILITY, "update_my_profile", "ask_user"), emptyList(), null, null, emptyList())
        val abilities = environment["abilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(abilities.any { "修改自己的资料" in it })
        val off = environment["abilities_off"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(off.any { it.startsWith("calendar") })
        assertNull(off.firstOrNull { it.startsWith("ask_user") })
    }
}
