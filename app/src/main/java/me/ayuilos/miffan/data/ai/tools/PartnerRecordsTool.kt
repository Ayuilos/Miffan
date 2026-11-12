package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.ayuilos.miffan.data.partner.PartnerRecords
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

const val MY_RECORDS_TOOL = "my_records"
private const val DEFAULT_RECORDS = 20
private const val MAX_RECORDS = 50

/** The partner's own approval and profile records, read-only; another partner's are never reachable. */
internal fun buildPartnerRecordsTools(isImMode: Boolean, assistantId: Uuid, records: PartnerRecords): List<Tool> {
    if (!isImMode) return emptyList()
    return listOf(Tool(
        name = MY_RECORDS_TOOL,
        description = """
            Read your own records, as the owner sees them on your profile. kind "approvals": the steps you may take
            without asking (standing), and your recent requests with what the owner decided (allowed, declined,
            answered, replied in chat...), how (card, standing approval...) and whether the owner later undid the change.
            kind "profile": every change to your profile and settings, newest first: when, who made it (the owner, you,
            another partner, the owner restoring an earlier version through undo or the history page, a backup), and
            what changed, with personality and learned preference lines removed and added. Use it when the owner asks
            what you are allowed to do, what changed, or whether something you did still stands. Read-only.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("approvals"); add("profile"); add("all") })
                    put("description", "Default all")
                })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Entries per kind, 1–$MAX_RECORDS (default $DEFAULT_RECORDS)") })
            })
        },
        needsApproval = { false },
        execute = { input ->
            val params = input as? JsonObject
            val kind = (params?.get("kind") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "all"
            val limit = ((params?.get("limit") as? JsonPrimitive)?.intOrNull ?: DEFAULT_RECORDS).coerceIn(1, MAX_RECORDS)
            val valid = kind in setOf("approvals", "profile", "all")
            val approvals = if (valid && kind != "profile") records.approvals(assistantId, limit) else null
            val profile = if (valid && kind != "approvals") records.profileChanges(assistantId, limit) else null
            val result = if (!valid) {
                buildJsonObject { put("success", false); put("error", "kind must be approvals, profile or all.") }
            } else buildJsonObject {
                approvals?.let { put("approvals", it) }
                if (profile != null) putJsonArray("profile") {
                    profile.forEach { change ->
                        addJsonObject {
                            put("at", records.time(change.at))
                            put("by", change.by)
                            putJsonArray("changes") { change.changes.forEach { add(it) } }
                        }
                    }
                }
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    ))
}
