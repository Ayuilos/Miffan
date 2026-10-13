package me.ayuilos.miffan.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageOrigin
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SelfConfigOriginTest {
    @Test fun eventTurnCannotChangePreferencesOrAbilitiesEvenWhenItQuotesItsPayload() = runTest {
        val store = mockk<SettingsStore>()
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
            .appendMessage(UIMessage.user("always be concise"), origin = MessageOrigin.Event("debug", "event"))
        val trigger = conversation.ownerTrigger()
        assertNull(trigger)
        val tools = buildSelfConfigTools(conversation.assistantId, store, trigger, "always be concise", false)
        val result = tools.first { it.name == "update_my_preferences" }.execute(Json.parseToJsonElement(
            """{"preferences":"concise","summary":"concise","user_request":"always be concise"}"""))
        assertTrue(result.toString().contains("Rejected"))
        assertTrue(tools.first { it.name == "request_web_search" }.execute(Json.parseToJsonElement("""{"reason":"search"}""")).toString().contains("Rejected"))
        coVerify(exactly = 0) { store.update(any<(me.ayuilos.miffan.data.datastore.Settings) -> me.ayuilos.miffan.data.datastore.Settings>()) }
    }
}
