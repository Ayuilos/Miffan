package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.PartnerMessageTool
import me.ayuilos.miffan.data.model.PartnerPair
import me.ayuilos.miffan.data.partner.DefaultPartnerChatService
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun buildPartnerMessageTools(
    caller: Conversation,
    settingsStore: SettingsStore,
    service: DefaultPartnerChatService,
): List<Tool> = listOf(
    Tool(
        name = "list_contacts",
        description = "List people you can contact using send_message, including whether the owner has authorized each partner pair.",
        parameters = { InputSchema.Obj(buildJsonObject { }) },
        execute = {
            val settings = settingsStore.settingsFlow.value
            val contacts = buildJsonArray {
                if (caller.chatId != null) add(buildJsonObject {
                    put("id", PartnerMessageTool.OWNER)
                    put("name", settings.displaySetting.userNickname.ifBlank { "主人" })
                    put("description", "The owner; send a note to your IM thread without generating a reply.")
                    put("authorized", true)
                })
                settings.assistants.filter { it.id != caller.assistantId }.forEach { assistant ->
                    add(buildJsonObject {
                        put("id", assistant.id.toString())
                        put("name", assistant.name)
                        put("description", assistant.systemPrompt.trim().lineSequence().firstOrNull().orEmpty().take(160))
                        put("authorized", PartnerPair.of(caller.assistantId, assistant.id) in settings.partnerContactGrants)
                    })
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject { put("contacts", contacts) }.toString()))
        },
    ),
    Tool(
        name = PartnerMessageTool.NAME,
        description = "Send a text message to a contact id from list_contacts. A new partner pair requires the owner's approval. Wait up to 90 seconds for a reply; pending means the reply will arrive later. Owner notes are available only inside partner chat perspectives.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put(PartnerMessageTool.ARG_TO, buildJsonObject { put("type", "string"); put("description", "Contact id from list_contacts") })
            put(PartnerMessageTool.ARG_TEXT, buildJsonObject { put("type", "string"); put("description", "Text to send") })
        }, listOf(PartnerMessageTool.ARG_TO, PartnerMessageTool.ARG_TEXT)) },
        needsApproval = { input -> service.needsApproval(caller, input.arg(PartnerMessageTool.ARG_TO)) },
        execute = { input -> listOf(UIMessagePart.Text(service.send(caller,
            input.arg(PartnerMessageTool.ARG_TO), input.arg(PartnerMessageTool.ARG_TEXT)).toString())) },
    ),
)

private fun JsonElement.arg(name: String): String = (this as? JsonObject)?.get(name)
    ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content.orEmpty()
