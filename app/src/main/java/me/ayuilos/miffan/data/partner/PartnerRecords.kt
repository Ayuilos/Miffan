package me.ayuilos.miffan.data.partner

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.ayuilos.miffan.data.appsettings.ThinkTier
import me.ayuilos.miffan.data.audit.AuditKind
import me.ayuilos.miffan.data.audit.AuditRepository
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.revision.AssistantRevisionRecorder
import me.ayuilos.miffan.data.revision.Revision
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionRepository
import me.ayuilos.miffan.data.revision.RevisionService
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.utils.diffLines
import me.ayuilos.miffan.utils.textLines
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/** Lines of a personality or preferences change given per side; the rest is counted. */
private const val DIFF_LINES = 12
private const val DIFF_LINE_CHARS = 200
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** One version of a partner's profile, told as what changed from the version before. */
data class ProfileChange(val at: Instant, val by: String, val toolCallId: String?, val changes: List<JsonObject>)

/** Something that happened to a partner outside its chat, for telling it at its next turn. */
data class OutsideEvent(val at: Instant, val text: String)

/**
 * A partner's own records, as the owner sees them on its profile: what it asked for and how the owner decided
 * (授权记录), and every change to its profile and settings (资料改动记录). Only the partner's own; never a header,
 * a request body or anything else that could hold a secret.
 */
class PartnerRecords(
    private val revisions: RevisionRepository,
    private val revisionService: RevisionService,
    private val audit: AuditRepository,
    /** The partner a conversation belongs to. */
    private val assistantOf: suspend (Uuid) -> Uuid?,
    private val settings: () -> Settings,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** Profile versions, newest first, back to [after] when given. Versions with nothing worth telling are left out. */
    suspend fun profileChanges(assistantId: Uuid, limit: Int, after: Instant? = null): List<ProfileChange> {
        val history = revisions.history(RevisionSubject.ASSISTANT, assistantId.toString()).first()
        val byId = history.associateBy { it.id }
        val current = settings()
        val found = mutableListOf<ProfileChange>()
        for (revision in history) {
            if (found.size >= limit || (after != null && revision.createdAt <= after)) break
            if (revision.author == RevisionAuthor.BASELINE) continue
            val parent = revision.parentId?.let { byId[it] ?: revisions.get(it) } ?: continue
            val changes = runCatching {
                describeProfileChange(AssistantRevisionRecorder.restore(parent.snapshot), AssistantRevisionRecorder.restore(revision.snapshot), current)
            }.getOrNull().orEmpty()
            if (changes.isNotEmpty()) found += ProfileChange(revision.createdAt, author(revision, assistantId, current), revision.toolCallId, changes)
        }
        return found
    }

    /** The steps it may take without asking, and its recent requests with the owner's decisions, newest first. */
    suspend fun approvals(assistantId: Uuid, limit: Int): JsonObject {
        val events = audit.observe(assistantId, emptySet(), limit).first()
        val undone = runCatching { revisionService.undoneToolCalls(events.mapNotNull { it.toolCallId }) }.getOrDefault(emptySet())
        val assistant = settings().assistants.firstOrNull { it.id == assistantId }
        return buildJsonObject {
            putJsonArray("standing") { assistant?.standingToolApprovals.orEmpty().sorted().forEach { add(it) } }
            putJsonArray("decisions") {
                events.forEach { event ->
                    addJsonObject {
                        put("at", time(event.at))
                        when (event.kind) {
                            AuditKind.SCREEN_TAKEN_OVER -> put("event", "the owner took over the computer screen")
                            AuditKind.SCREEN_HANDED_BACK -> put("event", "the owner handed the computer screen back")
                            else -> {
                                event.toolName?.let { put("step", it) }
                                put("what", event.summary)
                                event.decision?.let { put("decision", it.name.lowercase()) }
                                event.via?.let { put("how", it.name.lowercase()) }
                                if (event.toolCallId != null && event.toolCallId in undone) put("undone", true)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What happened to the partner after [after] that its chat does not show: the owner (or another partner, or a
     * backup) changing its profile or standing approvals, and the owner undoing one of [calls]. Oldest first.
     */
    suspend fun outsideEvents(assistantId: Uuid, calls: List<UIMessagePart.Tool>, after: Instant): List<OutsideEvent> {
        val profile = profileChanges(assistantId, limit = 50, after = after).filter { it.by != BY_YOU }.map { change ->
            OutsideEvent(change.at, "${whoChanged(change.by)}: ${change.changes.joinToString("; ") { renderChange(it) }}.")
        }
        val undone = calls.filter { it.isExecuted }.distinctBy { it.toolCallId }.mapNotNull { call ->
            val at = runCatching { revisionService.undoneAt(call.toolCallId) }.getOrNull()?.takeIf { it > after } ?: return@mapNotNull null
            val reason = runCatching { (call.inputAsJson() as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }.getOrNull()
            OutsideEvent(at, "The owner undid your ${call.toolName} call${reason?.let { " (\"${it.take(80)}\")" } ?: ""}: " +
                "none of it is in effect any more.")
        }
        return (profile + undone).sortedBy { it.at }
    }

    fun time(at: Instant): String = at.atZone(zone()).format(TIME)

    private fun time(epochMillis: Long): String = time(Instant.ofEpochMilli(epochMillis))

    private suspend fun author(revision: Revision, self: Uuid, settings: Settings): String = when (revision.author) {
        RevisionAuthor.USER -> BY_OWNER
        RevisionAuthor.RESTORE -> BY_RESTORE
        RevisionAuthor.BACKUP -> BY_BACKUP
        RevisionAuthor.BASELINE -> BY_OWNER
        RevisionAuthor.AGENT -> {
            val partner = revision.trigger?.conversationId?.let { runCatching { assistantOf(it) }.getOrNull() }
            when {
                partner == null || partner == self -> BY_YOU
                else -> "partner " + (settings.assistants.firstOrNull { it.id == partner }?.name?.ifBlank { null } ?: "(unnamed)")
            }
        }
    }

    companion object {
        const val BY_YOU = "you"
        const val BY_OWNER = "owner"
        const val BY_RESTORE = "owner, restoring an earlier version (undo or the history page)"
        const val BY_BACKUP = "a backup being restored"
    }
}

private fun whoChanged(by: String): String = when (by) {
    PartnerRecords.BY_OWNER -> "The owner changed your profile"
    PartnerRecords.BY_RESTORE -> "The owner restored an earlier version of your profile"
    PartnerRecords.BY_BACKUP -> "Your profile came back from a backup"
    else -> "${by.replaceFirstChar { it.uppercase() }} changed your profile"
}

/** What changed between two versions of a partner, named field by field. Headers, bodies and the like stay out. */
internal fun describeProfileChange(before: Assistant, after: Assistant, settings: Settings): List<JsonObject> = buildList {
    fun field(name: String, from: Any?, to: Any?) = add(buildJsonObject {
        put("field", name); put("from", from.toJson()); put("to", to.toJson())
    })
    if (before.name != after.name) field("name", before.name, after.name)
    if (before.avatar != after.avatar) add(buildJsonObject { put("field", "avatar") })
    textChange("personality", before.systemPrompt, after.systemPrompt)?.let(::add)
    textChange("learned_preferences", before.learnedPreferences, after.learnedPreferences)?.let(::add)
    if (before.chatModelId != after.chatModelId) field("model", modelName(before, settings), modelName(after, settings))
    if (before.reasoningLevel != after.reasoningLevel) field("thinking", thinking(before, settings), thinking(after, settings))
    if (before.maxTokens != after.maxTokens) field("reply_limit", before.maxTokens ?: "none", after.maxTokens ?: "none")
    if (before.contextMessageLimit != after.contextMessageLimit) {
        field("context_messages", before.contextMessageLimit.takeIf { it > 0 } ?: "all", after.contextMessageLimit.takeIf { it > 0 } ?: "all")
    }
    if (before.temperature != after.temperature) field("temperature", before.temperature ?: "default", after.temperature ?: "default")
    if (before.enableWebSearch != after.enableWebSearch) field("web_search", before.enableWebSearch, after.enableWebSearch)
    if (before.enableMemory != after.enableMemory) field("memory", before.enableMemory, after.enableMemory)
    if (before.useGlobalMemory != after.useGlobalMemory) field("shared_memory", before.useGlobalMemory, after.useGlobalMemory)
    if (before.computerUse != after.computerUse) field("computer_use", before.computerUse.name.lowercase(), after.computerUse.name.lowercase())
    if (before.workspaceId != after.workspaceId) field("workspace", before.workspaceId != null, after.workspaceId != null)
    if (before.workspaceShellEnabled != after.workspaceShellEnabled) field("shell", before.workspaceShellEnabled, after.workspaceShellEnabled)
    if (before.workspaceShellApprovalRequired != after.workspaceShellApprovalRequired) {
        field("shell_asks_first", before.workspaceShellApprovalRequired, after.workspaceShellApprovalRequired)
    }
    val granted = after.standingToolApprovals - before.standingToolApprovals
    val revoked = before.standingToolApprovals - after.standingToolApprovals
    if (granted.isNotEmpty() || revoked.isNotEmpty()) add(buildJsonObject {
        put("field", "standing_approvals")
        if (granted.isNotEmpty()) putJsonArray("granted") { granted.sorted().forEach { add(it) } }
        if (revoked.isNotEmpty()) putJsonArray("revoked") { revoked.sorted().forEach { add(it) } }
    })
    val named = after.copy(name = before.name, avatar = before.avatar, systemPrompt = before.systemPrompt,
        learnedPreferences = before.learnedPreferences, chatModelId = before.chatModelId, reasoningLevel = before.reasoningLevel,
        maxTokens = before.maxTokens, contextMessageLimit = before.contextMessageLimit, temperature = before.temperature,
        enableWebSearch = before.enableWebSearch, enableMemory = before.enableMemory, useGlobalMemory = before.useGlobalMemory,
        computerUse = before.computerUse, workspaceId = before.workspaceId, workspaceShellEnabled = before.workspaceShellEnabled,
        workspaceShellApprovalRequired = before.workspaceShellApprovalRequired, standingToolApprovals = before.standingToolApprovals,
        // Bookkeeping that follows from the fields above, not a change of its own.
        workspaceScopeId = before.workspaceScopeId, workspacePermissionRevision = before.workspacePermissionRevision,
        workspaceShellApprovalTarget = before.workspaceShellApprovalTarget, workspaceShellApprovalVia = before.workspaceShellApprovalVia)
    if (named != before) add(buildJsonObject { put("field", "other_settings") })
}

/** Lines taken out of and put into a long text, a dozen per side at most. */
private fun textChange(field: String, before: String, after: String): JsonObject? {
    if (before == after) return null
    val diff = diffLines(before.textLines(), after.textLines()).filter { it.first.isNotBlank() }
    val removed = diff.filter { !it.second }.map { it.first }
    val added = diff.filter { it.second }.map { it.first }
    return buildJsonObject {
        put("field", field)
        fun lines(key: String, lines: List<String>) {
            if (lines.isEmpty()) return
            putJsonArray(key) { lines.take(DIFF_LINES).forEach { add(it.trim().take(DIFF_LINE_CHARS)) } }
            if (lines.size > DIFF_LINES) put("${key}_more", lines.size - DIFF_LINES)
        }
        lines("removed", removed)
        lines("added", added)
    }
}

private fun modelName(assistant: Assistant, settings: Settings): String =
    assistant.chatModelId?.let { id -> settings.findModelById(id)?.let { it.displayName.ifBlank { it.modelId } } ?: "a model no longer set up" }
        ?: "default"

private fun thinking(assistant: Assistant, settings: Settings): String {
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return assistant.reasoningLevel.name.lowercase()
    return runCatching { ThinkTier.of(assistant.reasoningLevel, model).id }.getOrDefault(assistant.reasoningLevel.name.lowercase())
}

private fun Any?.toJson(): JsonPrimitive = when (this) {
    null -> JsonPrimitive("none")
    is Boolean -> JsonPrimitive(if (this) "on" else "off")
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

/** One change as a short phrase, for telling the partner in passing. */
internal fun renderChange(change: JsonObject): String {
    val field = change["field"]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun text(key: String) = change[key]?.jsonPrimitive?.contentOrNull
    fun list(key: String) = (change[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    return when (field) {
        "avatar" -> "avatar changed"
        "other_settings" -> "other settings changed"
        "personality", "learned_preferences" -> buildList {
            list("removed").takeIf { it.isNotEmpty() }?.let { lines ->
                add("removed ${lines.joinToString(" / ") { "\"$it\"" }}${text("removed_more")?.let { " and $it more lines" } ?: ""}")
            }
            list("added").takeIf { it.isNotEmpty() }?.let { lines ->
                add("added ${lines.joinToString(" / ") { "\"$it\"" }}${text("added_more")?.let { " and $it more lines" } ?: ""}")
            }
        }.ifEmpty { listOf("reworded") }.joinToString(", ", prefix = "${field.replace('_', ' ')}: ")
        "standing_approvals" -> buildList {
            list("granted").takeIf { it.isNotEmpty() }?.let { add("may now ${it.joinToString()} without asking") }
            list("revoked").takeIf { it.isNotEmpty() }?.let { add("must ask again before ${it.joinToString()}") }
        }.joinToString(", ", prefix = "standing approvals: ")
        else -> "${field.replace('_', ' ')} ${text("from")} → ${text("to")}"
    }
}
