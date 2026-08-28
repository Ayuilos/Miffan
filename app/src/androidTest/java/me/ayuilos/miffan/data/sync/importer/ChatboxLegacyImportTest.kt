package me.ayuilos.miffan.data.sync.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatboxLegacyImportTest {
    @Test
    fun legacyJsonKeepsStableIdsSystemPromptsAndDuplicateSkipping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The picker uses a temporary ZIP suffix for either format; detection must use content.
        val backup = File.createTempFile("legacy-chatbox-", ".zip", context.cacheDir)
        try {
            backup.writeText(
                """{
                  "settings":{"providers":{}},
                  "session:legacy":{
                    "id":"legacy", "name":"Legacy conversation",
                    "messages":[
                      {"id":"system", "role":"system", "content":"Be helpful"},
                      {"id":"user", "role":"user", "timestamp":1000, "contentParts":[
                        {"type":"text", "text":"Hello"},
                        {"type":"image", "storageKey":"unavailable"}
                      ]}
                    ]
                  }
                }""".trimIndent()
            )
            val conversations = mutableListOf<Conversation>()
            val assistantId = Uuid.random()
            val result = ChatboxImporter.importStreaming(
                file = backup,
                assistantId = assistantId,
                providers = emptyList(),
                onConversation = { conversations += it },
            )
            assertEquals(1, result.parsedConversations)
            assertEquals(1, result.skippedImageParts)
            assertEquals(0, result.importedImageParts)
            assertTrue(result.hasConversationSystemPrompt)
            val conversation = conversations.single()
            assertEquals(stableUuid("chatbox:session:legacy"), conversation.id)
            assertEquals(assistantId, conversation.assistantId)
            assertEquals("Be helpful", conversation.customSystemPrompt)
            val node = conversation.messageNodes.single()
            assertEquals(stableUuid("chatbox:node:legacy:user"), node.id)
            assertEquals(stableUuid("chatbox:message:user"), node.currentMessage.id)
            assertEquals("Hello", (node.currentMessage.parts.single() as UIMessagePart.Text).text)

            val repeated = ChatboxImporter.importStreaming(
                file = backup,
                assistantId = assistantId,
                providers = emptyList(),
                shouldImportConversation = { it != conversation.id },
                onConversation = { error("Duplicate conversation imported") },
            )
            assertEquals(0, repeated.parsedConversations)
            assertEquals(0, repeated.skippedImageParts)
        } finally {
            backup.delete()
        }
    }

    private fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString())
}
