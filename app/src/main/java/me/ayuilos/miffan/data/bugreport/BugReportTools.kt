package me.ayuilos.miffan.data.bugreport

import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Both modes, same per-turn authority and final R9 wrapper as every other tool. */
fun createBugReportTools(
    options: List<LocalToolOption>,
    access: AgentAccess,
    diagnostics: suspend () -> AppDiagnostics,
): List<Tool> = if (LocalToolOption.BugReport in options) listOf(
    createAppDiagnosticsTool(access, diagnostics), createDraftBugReportTool(access, diagnostics),
) else emptyList()

private val reportParameters = listOf("title", "description", "actual", "expected", "logs", "provider", "model", "app_version", "android_version", "device_model")

@Suppress("UNUSED_PARAMETER") // Captures the turn authority, but reads no caller or partner configuration.
fun createDraftBugReportTool(access: AgentAccess, diagnostics: suspend () -> AppDiagnostics): Tool = Tool(
    name = "draft_bug_report",
    description = "Draft one Miffan bug for the owner to approve every time. No action occurs before approval. Never include private chat, API keys, file contents or internal addresses. description must include reproduction steps; logs must contain only relevant app_diagnostics error summaries. title is 1–80 characters without bug:. Missing environment fields are filled locally. After approval returns a sanitized GitHub issue-form link only; the owner needs a GitHub account and reviews and submits publicly in their browser. You cannot know whether they submitted it.",
    parameters = { InputSchema.Obj(
        properties = buildJsonObject {
            reportParameters.forEach { name -> put(name, buildJsonObject {
                put("type", "string")
                if (name == "title") { put("minLength", 1); put("maxLength", 80) }
                if (name == "logs") put("description", "Only relevant sanitized error summaries from app_diagnostics")
            }) }
        },
        required = listOf("title", "description", "actual", "expected"),
    ) },
    needsApproval = { true },
    execute = { input ->
        val result = try {
            val draft = parseBugReportDraft(input)
            val environment = diagnostics()
            val filled = draft.copy(
                app_version = draft.app_version?.takeIf { it.isNotBlank() } ?: environment.appVersion,
                android_version = draft.android_version?.takeIf { it.isNotBlank() }
                    ?: "Android ${environment.androidVersion.release} (SDK ${environment.androidVersion.sdk})",
                device_model = draft.device_model?.takeIf { it.isNotBlank() }
                    ?: "${environment.device.manufacturer} ${environment.device.model}",
            )
            // No settings or chat lookup, no file/network write, and no browser intent.
            buildJsonObject {
                put("success", true)
                put("issue_url", buildBugReportIssueUrl(filled))
                put("note", "The owner opens this link to review and submit on GitHub; you cannot know whether it was submitted.")
            }
        } catch (error: IllegalArgumentException) {
            buildJsonObject { put("success", false); put("error", error.message ?: "Invalid bug report") }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

internal fun parseBugReportDraft(input: JsonElement): BugReportDraft {
    val objectInput = input as? JsonObject ?: throw IllegalArgumentException("Parameters must be an object")
    require(objectInput.keys.all { it in reportParameters }) { "Unknown bug report parameter" }
    fun value(key: String): String? {
        val raw = objectInput[key] ?: return null
        require(raw is JsonPrimitive && raw.isString) { "$key must be a string" }
        return raw.content
    }
    return BugReportDraft(
        title = value("title") ?: "", description = value("description") ?: "",
        actual = value("actual") ?: "", expected = value("expected") ?: "",
        logs = value("logs"), provider = value("provider"), model = value("model"),
        app_version = value("app_version"), android_version = value("android_version"), device_model = value("device_model"),
    ).also { it.validate() }
}
