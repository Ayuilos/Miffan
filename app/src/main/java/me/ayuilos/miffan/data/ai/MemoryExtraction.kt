package me.ayuilos.miffan.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.model.AssistantMemory
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * Background memory extraction: after a reply finishes, the fast model reads the recent turns and
 * proposes memory changes, so remembering does not depend on the chat model choosing to call
 * memory_tool. It runs right away when the latest user message looks memorable, and every
 * [MEMORY_EXTRACTION_INTERVAL] user turns as a fallback.
 */
const val MEMORY_EXTRACTION_INTERVAL = 5

private const val MAX_OPERATIONS = 5
private const val MAX_MESSAGE_CHARS = 800

private val memorySignal = Regex(
    listOf(
        "记住", "别忘", "不要忘", "以后(都|请|别|不要|记得)", "从现在(开始|起)",
        "我叫", "叫我", "我的名字", "我是(一名|一个|个|做|学|在)", "我(很|最|特别|不太|不)?(喜欢|讨厌|爱|怕|习惯)",
        "我(住在|搬到|在.{1,8}(工作|上班|上学|读书))", "我.{0,6}(打算|计划)", "我准备", "我的(生日|工作|专业|职业|孩子|老婆|老公|对象|猫|狗)",
        "我养了", "其实我",
        "remember", "don'?t forget", "from now on", "call me", "my name",
        "i (really )?(like|love|hate|prefer|enjoy)", "i (live|work|study)", "i'?m (planning|going to|moving)",
        "i plan", "my (birthday|job|wife|husband|partner|kid|son|daughter|cat|dog)",
    ).joinToString("|"),
    RegexOption.IGNORE_CASE,
)

internal fun hasMemorySignal(text: String): Boolean = memorySignal.containsMatchIn(text)

/** The turns to review, or null when this reply should not trigger an extraction. */
internal fun memoryExtractionWindow(messages: List<UIMessage>): List<UIMessage>? {
    val userIndices = messages.indices.filter { messages[it].role == MessageRole.USER }
    val latestUser = userIndices.lastOrNull() ?: return null
    val fallback = userIndices.size % MEMORY_EXTRACTION_INTERVAL == 0
    // When the chat model already saved something this turn, the signal is handled; only the
    // fallback still reviews the whole window.
    val savedThisTurn = messages.drop(latestUser + 1)
        .any { message -> message.getTools().any { it.toolName == "memory_tool" } }
    val signal = hasMemorySignal(messages[latestUser].toText()) && !savedThisTurn
    val turns = when {
        fallback -> MEMORY_EXTRACTION_INTERVAL
        signal -> 2
        else -> return null
    }
    return messages.drop(userIndices[maxOf(0, userIndices.size - turns)])
}

internal fun buildMemoryExtractionPrompt(
    memories: List<AssistantMemory>,
    window: List<UIMessage>,
    today: String,
): String = buildString {
    appendLine("You maintain long-term memories about the user of a chat app. Today is $today.")
    appendLine("Read the recent conversation and decide whether any memory should be added or updated.")
    appendLine()
    appendLine("Remember only lasting information about the user: what to call them, identity, job, location, family, pets, routines, lasting preferences or dislikes (including how the assistant should talk to them), ongoing plans, goals, deadlines and upcoming events (with dates), and corrections to what was known.")
    appendLine("Do not remember small talk, one-off requests, details only useful in this conversation, things the assistant said, or fictional/role-play content.")
    appendLine("Never remember sensitive information (ethnicity, religion, sexual orientation, political views, sex life, criminal records).")
    appendLine("If a related memory already exists, edit it (merge the new detail in) instead of creating a duplicate. Skip anything already covered.")
    appendLine("Write each memory as one short standalone sentence about the user, in the language the user writes in.")
    appendLine()
    appendLine("<memories>")
    appendLine(
        buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }.toString()
    )
    appendLine("</memories>")
    appendLine()
    appendLine("<conversation>")
    window.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .forEach { appendLine(it.summaryAsText(maxLength = MAX_MESSAGE_CHARS)) }
    appendLine("</conversation>")
    appendLine()
    appendLine("Reply with a JSON array only, no other text. Use [] when nothing should change. At most $MAX_OPERATIONS items:")
    appendLine("""[{"action":"create","content":"..."},{"action":"edit","id":12,"content":"..."}]""")
}

internal sealed interface MemoryOperation {
    data class Create(val content: String) : MemoryOperation
    data class Edit(val id: Int, val content: String) : MemoryOperation
}

/**
 * Parses the fast model's reply leniently (code fences, surrounding prose). Edits must target an
 * existing memory; deletion is left to the chat model's memory_tool, where the user asked for it.
 */
internal fun parseMemoryOperations(reply: String, existingIds: Set<Int>): List<MemoryOperation> {
    val start = reply.indexOf('[')
    val end = reply.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val array = runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)) as? JsonArray }
        .getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        when (item["action"]?.jsonPrimitive?.contentOrNull) {
            "create" -> MemoryOperation.Create(content)
            "edit" -> item["id"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it in existingIds }
                ?.let { MemoryOperation.Edit(it, content) }
            else -> null
        }
    }.distinct().take(MAX_OPERATIONS)
}
