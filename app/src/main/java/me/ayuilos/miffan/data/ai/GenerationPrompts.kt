package me.ayuilos.miffan.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.model.AssistantMemory
import me.ayuilos.miffan.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        appendLine("<memories>")
        appendLine("Long-term memories you saved with memory_tool in earlier conversations. Use them naturally; do not recite them unless the user asks.")
        if (memories.isEmpty()) {
            appendLine("(none yet)")
        } else {
            val json = buildJsonArray {
                memories.forEach { memory ->
                    add(buildJsonObject {
                        put("id", memory.id)
                        put("content", memory.content)
                    })
                }
            }
            appendLine(JsonInstantPretty.encodeToString(json))
        }
        appendLine("</memories>")
        appendLine("<memory_rules>")
        appendLine("Call memory_tool in the same turn, before your reply, whenever the user:")
        appendLine("- shares a lasting fact about themselves (what to call them, job, location, family, pets, routines)")
        appendLine("- states a lasting preference or dislike, including how you should talk to them")
        appendLine("- mentions an ongoing plan, goal, deadline or upcoming event worth following up on")
        appendLine("- corrects something you remembered or assumed about them")
        appendLine("- asks you to remember something, or says \"from now on\" / \"don't forget\"")
        appendLine("If a related memory already exists, edit it instead of creating a duplicate. If the user asks you to forget something, delete it.")
        appendLine("Do not save small talk, details only useful in this conversation, or sensitive information (ethnicity, religion, sexual orientation, political views, sex life, criminal records).")
        appendLine("Saving is silent: do not announce it or quote memory content unless the user asks.")
        append("</memory_rules>")
        appendLine()
    }
