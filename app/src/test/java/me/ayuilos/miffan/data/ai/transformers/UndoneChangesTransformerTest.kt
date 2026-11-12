package me.ayuilos.miffan.data.ai.transformers

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UndoneChangesTransformerTest {
    private fun tool(id: String, executed: Boolean = true) = UIMessagePart.Tool(
        toolCallId = id, toolName = "change_app_settings", input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("""{"success":true}""")) else emptyList(),
    )

    @Test
    fun theUndoneCallsResultSaysSoAndNothingElseChanges() = runBlocking {
        val asked = mutableListOf<List<String>>()
        val transformer = UndoneChangesTransformer { ids -> asked += ids; setOf("undone") }
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("dark mode please")))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("undone"), tool("kept"), tool("waiting", executed = false)))
        val other = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("kept-2")))

        val out = transformer.transform(mockk<TransformerContext>(), listOf(user, reply, other))

        // Only finished calls are looked up.
        assertEquals(listOf(listOf("undone", "kept", "kept-2")), asked)
        assertSame(user, out[0])
        assertSame(other, out[2])
        val tools = out[1].parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf(UIMessagePart.Text("""{"success":true}"""), UIMessagePart.Text(UNDONE_CHANGE_NOTE)), tools[0].output)
        assertEquals(tool("kept"), tools[1])
        assertEquals(tool("waiting", executed = false), tools[2])
        // The stored message is left as it was.
        assertEquals(1, (reply.parts[0] as UIMessagePart.Tool).output.size)
    }

    @Test
    fun aFailedLookupLeavesTheRequestAlone() = runBlocking {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("a"))))
        val out = UndoneChangesTransformer { error("database closed") }.transform(mockk<TransformerContext>(), messages)
        assertSame(messages, out)
    }
}
