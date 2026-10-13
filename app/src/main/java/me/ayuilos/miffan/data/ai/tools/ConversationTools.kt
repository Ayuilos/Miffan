package me.ayuilos.miffan.data.ai.tools

import kotlin.uuid.Uuid
import me.ayuilos.miffan.data.ai.AgentAccess
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
import me.ayuilos.miffan.data.model.OWNER_PARTICIPANT_ID
import me.ayuilos.miffan.data.partner.partnerDisplayName
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
    access: AgentAccess,
    settings: Settings,
    partnerChats: PartnerChatService? = null,
): List<Tool> {
    val assistantId = access.callerId
    val recallScope = if (access.ownerInitiated) "Only your own conversations with the owner are accessible."
        else "This partner-initiated turn can recall only partner chats you participate in; owner conversations are unavailable."
    return listOf(
        Tool(
            name = "recent_chats",
            description = """
                List recent conversations accessible to you. $recallScope
                ${if (settings.isImMode) "Returns titles and dates, ordered by most recent activity." else "Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated."}
                ${if (settings.isImMode) "Also covers your private chats and group chats with other partners that you took part in." else ""}
                Use this when you need quick context about what the user has been discussing lately.
                Only titles and dates are returned; use `conversation_search` to look up the actual content.
                ${if (settings.isImMode) "For partner chats, use read_partner_chat with the returned chat_id to read the conversation directly, without guessing keywords." else ""}
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
                val recent = if (access.ownerInitiated) conversationRepo.getRecentConversations(
                    assistantId = assistantId,
                    limit = limit,
                    newestFirst = settings.isImMode,
                ) else emptyList()
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
                Full-text search across conversations accessible to you. $recallScope
                Use focused keywords. Run multiple searches with different keywords if needed.
                ${if (settings.isImMode) "Also covers your private chats and group chats with other partners that you took part in." else ""}
                Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
                ${if (settings.isImMode) "For private chats and group chats, use read_partner_chat with a result's chat_id to read more of the conversation." else ""}
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
                        access = access)
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
                                result.chatId?.let {
                                    put("chat_id", it)
                                    put("read_hint", "Use read_partner_chat with this chat_id to read recent messages.")
                                }
                            }
                        })
                    }
                }
                listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
            }
        )
    ) + if (settings.isImMode) listOf(readPartnerChatTool(assistantId, settings, requireNotNull(partnerChats))) else emptyList()
}

private fun readPartnerChatTool(assistantId: Uuid, settings: Settings, chats: PartnerChatService): Tool = Tool(
    name = "read_partner_chat",
    description = """
        Read the recent final messages of private chats and group chats you are a member of.
        Use chat_id from recent_chats or conversation_search to find who you talked with and what was discussed.
        Returns the chat title, type, and recent messages in chronological order, with sender names and timestamps.
        Each message is limited to 2000 characters. You cannot read a chat you are not a member of.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("chat_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Partner chat UUID from recent_chats or conversation_search")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of recent messages (default: 20, max: 50)")
                })
            },
            required = listOf("chat_id"),
        )
    },
    execute = { args ->
        fun errorResult(message: String) = listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(
            buildJsonObject { put("error", message) }
        )))
        val id = (args.jsonObject["chat_id"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (id == null) {
            errorResult("chat_id must be a valid partner chat UUID.")
        } else {
            val chat = chats.observeChat(id).first()
            when {
                chat == null -> errorResult("Partner chat not found.")
                assistantId !in chat.memberIds -> errorResult("You are not a member of this partner chat.")
                else -> {
                    val limit = (args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 50)
                    val messages = chats.observeMessages(id).first().sortedBy { it.createdAt }.takeLast(limit)
                    // Membership can change while messages are loaded; recheck before exposing content.
                    val currentChat = chats.observeChat(id).first()
                    when {
                        currentChat == null -> errorResult("Partner chat not found.")
                        assistantId !in currentChat.memberIds -> errorResult("You are not a member of this partner chat.")
                        else -> listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(buildJsonObject {
                            put("chat_id", id.toString())
                            put("title", recallChatTitle(currentChat, assistantId, settings))
                            put("type", currentChat.kind.name.lowercase())
                            put("messages", buildJsonArray {
                                messages.forEach { message ->
                                    add(buildJsonObject {
                                        put("sender", if (message.senderId == OWNER_PARTICIPANT_ID)
                                            settings.displaySetting.userNickname.ifBlank { "主人" }
                                            else partnerDisplayName(settings.getAssistantById(message.senderId)))
                                        put("sender_id", message.senderId.toString())
                                        put("created_at", message.createdAt.toString())
                                        val text = message.text
                                        put("text", if (text.codePointCount(0, text.length) > 2000)
                                            text.substring(0, text.offsetByCodePoints(0, 2000)) else text)
                                    })
                                }
                            })
                        })))
                    }
                }
            }
        }
    },
)

private fun recallChatSource(chat: PartnerChat): String =
    if (chat.kind == PartnerChatKind.GROUP) "group_chat" else "partner_chat"

private fun recallChatTitle(chat: PartnerChat, assistantId: Uuid, settings: Settings): String =
    if (chat.kind == PartnerChatKind.GROUP) partnerChatTitle(chat, settings)
    else "和 ${chat.memberIds.filter { it != assistantId }.joinToString(" · ") {
        partnerDisplayName(settings.getAssistantById(it))
    }} 的私聊"
