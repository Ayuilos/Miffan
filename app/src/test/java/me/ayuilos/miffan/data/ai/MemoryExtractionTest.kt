package me.ayuilos.miffan.data.ai

import me.ayuilos.miffan.data.ai.MemoryOperation.Create
import me.ayuilos.miffan.data.ai.MemoryOperation.Edit
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionTest {
    private fun turns(vararg userTexts: String): List<UIMessage> = userTexts.flatMap {
        listOf(UIMessage.user(it), UIMessage.assistant("ok"))
    }

    @Test
    fun signalsCatchLastingFactsButNotEverydayPhrasing() {
        listOf("记住我不吃香菜", "以后都叫我阿星", "我是一名护士", "我在上海工作", "我下个月打算去日本",
            "From now on reply in English", "Call me Sam", "I really love hiking")
            .forEach { assertTrue(it, hasMemorySignal(it)) }
        listOf("帮我写一段代码", "我是说上一个问题", "我在想这个怎么做", "以后再说吧", "What's the weather?")
            .forEach { assertFalse(it, hasMemorySignal(it)) }
    }

    @Test
    fun ordinaryTurnsDoNotTriggerAnExtraction() {
        assertNull(memoryExtractionWindow(turns("你好", "今天天气怎么样")))
    }

    @Test
    fun aSignalReviewsTheLatestTwoTurns() {
        val messages = turns("你好", "帮我看看这段", "记住我喜欢简短的回答")
        assertEquals(messages.drop(2), memoryExtractionWindow(messages))
    }

    @Test
    fun aSignalAlreadyHandledByMemoryToolIsSkipped() {
        val messages = turns("你好", "记住我喜欢简短的回答").dropLast(1) + UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "1", toolName = "memory_tool", input = "{}", output = emptyList())),
        )
        assertNull(memoryExtractionWindow(messages))
    }

    @Test
    fun everyFifthUserTurnReviewsTheLastFiveTurns() {
        val messages = turns("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
        assertEquals(messages.drop(10), memoryExtractionWindow(messages))
    }

    @Test
    fun parsingToleratesFencesAndDropsInvalidOperations() {
        val reply = """
            Here you go:
            ```json
            [{"action":"create","content":" Prefers tea "},
             {"action":"edit","id":3,"content":"Lives in Hangzhou"},
             {"action":"edit","id":99,"content":"Unknown id"},
             {"action":"delete","id":3},
             {"action":"create","content":""}]
            ```
        """.trimIndent()
        assertEquals(
            listOf(Create("Prefers tea"), Edit(3, "Lives in Hangzhou")),
            parseMemoryOperations(reply, existingIds = setOf(3)),
        )
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("nothing to save", setOf(3)))
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("[]", setOf(3)))
    }
}
