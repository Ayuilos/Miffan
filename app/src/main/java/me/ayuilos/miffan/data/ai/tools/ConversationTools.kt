package me.ayuilos.miffan.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.db.fts.MessageSearchSort
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.data.partner.PartnerChatService
import me.ayuilos.miffan.data.partner.partnerChatTitle
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.utils.JsonInstantPretty
import me.ayuilos.miffan.utils.toLocalDate
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    settings: Settings,
    partnerChats: PartnerChatService? = null,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            ${if (settings.isImMode) "Returns titles and dates, ordered by most recent activity." else "Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated."}
            ${if (settings.isImMode) "Also covers your private chats and group chats with other partners that you took part in." else ""}
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
                newestFirst = settings.isImMode,
            )
            val entries = recent.map { conversation ->
                conversation.updateAt to buildJsonObject {
                    put("id", conversation.id.toString())
                    put("title", conversation.title.ifBlank { "Untitled" })
                    put("last_chat", conversation.updateAt.toLocalDate())
                    if (settings.isImMode) put("source", "owner_thread")
                }
            } + if (settings.isImMode) {
                requireNotNull(partnerChats).observeSummaries().first()
                    .filter { assistantId in it.chat.memberIds }
                    .map { summary ->
                        summary.lastActivity to buildJsonObject {
                            put("id", summary.chat.id.toString())
                            put("title", recallChatTitle(summary.chat, assistantId, settings))
                            put("last_chat", summary.lastActivity.toLocalDate())
                            put("source", recallChatSource(summary.chat))
                            put("chat_id", summary.chat.id.toString())
                        }
                    }
            } else emptyList()
            val ordered = if (settings.isImMode) entries.sortedByDescending { it.first } else entries
            val payload = buildJsonArray { ordered.take(limit).forEach { add(it.second) } }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across the user's past conversations to recall specific information they mentioned before.
            Use focused keywords. Run multiple searches with different keywords if needed.
            ${if (settings.isImMode) "Also covers your private chats and group chats with other partners that you took part in." else ""}
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = conversationRepo
                .searchMessages(query, MessageSearchSort.RELEVANCE,
                    perspectiveAssistantId = assistantId.takeIf { settings.isImMode })
                .take(limit)
            val chats = if (settings.isImMode) results.mapNotNull { it.chatId }.distinct().associateWith {
                requireNotNull(partnerChats).observeChat(Uuid.parse(it)).first()
            } else emptyMap()
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        put("title", chats[result.chatId]?.let { recallChatTitle(it, assistantId, settings) }
                            ?: result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                        if (settings.isImMode) {
                            put("source", if (result.chatId == null) "owner_thread"
                                else chats[result.chatId]?.let(::recallChatSource) ?: "partner_chat")
                            result.chatId?.let { put("chat_id", it) }
                        }
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

private fun recallChatSource(chat: PartnerChat): String =
    if (chat.kind == PartnerChatKind.GROUP) "group_chat" else "partner_chat"

private fun recallChatTitle(chat: PartnerChat, assistantId: Uuid, settings: Settings): String =
    if (chat.kind == PartnerChatKind.GROUP) partnerChatTitle(chat, settings)
    else "和 ${chat.memberIds.filter { it != assistantId }.joinToString(" · ") {
        settings.getAssistantById(it)?.name?.ifBlank { "伙伴" } ?: "伙伴"
    }} 的私聊"
