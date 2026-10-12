package me.ayuilos.miffan.data.bugreport

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.ai.AgentAccess
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.ai.withAgentRelayApproval
import me.ayuilos.miffan.data.model.Assistant
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class BugReportDraftTest {
    private val access = AgentAccess.forAssistant(Assistant(), true)
    private val environment = AppDiagnostics("4.1.4", 178030, "debug", AndroidVersion("15", 35), DiagnosticDevice("Maker", "Phone"), "easy", "zh-CN")
    private val report = BugReportDraft("发送失败 & 🐳", "1. 点击发送\n2. 发生错误 + & = ?", "出现错误", "正常回复", "HTTP failed", "Provider", "Model", "4.1.4", "Android 15", "Phone")
    private val input = buildJsonObject {
        put("title", report.title); put("description", report.description)
        put("actual", report.actual); put("expected", report.expected)
    }
    private fun decode(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val pair = it.split("=", limit = 2)
        pair[0] to URLDecoder.decode(pair[1], "UTF-8")
    }
    private suspend fun execute(input: JsonElement, diagnostics: suspend () -> AppDiagnostics = { environment }): JsonObject {
        val tool = createDraftBugReportTool(access, diagnostics)
        return Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun encodedFieldsRoundTripAndMatchExistingFormIds() {
        val url = buildBugReportIssueUrl(report)
        assertTrue(url.startsWith("https://github.com/Ayuilos/Miffan/issues/new?"))
        val fields = decode(url)
        assertEquals("bug-report.yml", fields["template"])
        assertEquals("bug: ${report.title}", fields["title"])
        assertEquals(report.description, fields["description"])
        assertEquals(report.actual, fields["actual"]); assertEquals(report.expected, fields["expected"])
        assertEquals(report.logs, fields["logs"]); assertEquals(report.provider, fields["provider"])
        assertEquals(report.model, fields["model"]); assertEquals(report.app_version, fields["app-version"])
        assertEquals(report.android_version, fields["android-version"]); assertEquals(report.device_model, fields["device-model"])
    }

    @Test fun everyFieldIsRedactedIncludingMarkdown() {
        val secret = "sk-secret https://user:secret@example.com/private?token=secret me@example.org 10.0.0.1"
        val draft = BugReportDraft("sk-secret", secret, secret, secret, secret, secret, secret, secret, secret, secret)
        val decoded = decode(buildBugReportIssueUrl(draft))
        decoded.filterKeys { it != "template" }.values.forEach {
            assertFalse(it.contains("sk-secret")); assertFalse(it.contains("/private"))
            assertFalse(it.contains("me@example.org")); assertFalse(it.contains("10.0.0.1"))
        }
        val markdown = draft.toMarkdown()
        assertFalse(markdown.contains("sk-secret")); assertFalse(markdown.contains("/private"))
    }

    @Test fun logsTruncateBeforeDescriptionAndKeepNoticeWithinLimit() {
        val large = "很长的一段信息 \n".repeat(2000)
        val logsOnly = decode(buildBugReportIssueUrl(report.copy(logs = large)))
        assertEquals(report.description, logsOnly["description"])
        assertTrue(logsOnly.getValue("logs").endsWith(BUG_REPORT_TRUNCATION_NOTE))
        val url = buildBugReportIssueUrl(report.copy(description = large, logs = large))
        val both = decode(url)
        assertTrue(url.length <= BUG_REPORT_URL_LIMIT)
        assertEquals(BUG_REPORT_TRUNCATION_NOTE, both["logs"])
        assertTrue(both.getValue("description").endsWith(BUG_REPORT_TRUNCATION_NOTE))
        val withoutLogs = buildBugReportIssueUrl(report.copy(description = large, logs = null))
        assertTrue(withoutLogs.length <= BUG_REPORT_URL_LIMIT)
        assertFalse(decode(withoutLogs).containsKey("logs"))
    }

    @Test fun oversizedNonTruncatableFieldsFailInsteadOfReturningInvalidUrl() = runTest {
        val result = execute(JsonObject(input + ("actual" to JsonPrimitive("太多信息 ".repeat(3000)))))
        assertFalse(result.getValue("success").jsonPrimitive.boolean)
        assertFalse(result.containsKey("issue_url"))
    }

    @Test fun markdownUsesFullSanitizedReportWithOptionalSections() {
        val markdown = report.toMarkdown()
        assertTrue(markdown.startsWith("# bug: ${report.title}\n\n### 问题描述 | Problem Description\n\n${report.description}"))
        assertTrue(markdown.contains("### 实际行为 | Actual Behavior\n\n${report.actual}"))
        assertTrue(markdown.contains("### 期望行为 | Expected Behavior\n\n${report.expected}"))
        assertTrue(markdown.contains("### 日志 | Logs\n\n${report.logs}"))
        assertTrue(markdown.endsWith("### 手机型号 | Device Model\n\n${report.device_model}"))
        assertFalse(report.copy(logs = null).toMarkdown().contains("### 日志"))
    }

    @Test fun approvalsAreAlwaysRequiredAndDoNotEvenReadDiagnostics() = runTest {
        var calls = 0
        val tool = createDraftBugReportTool(access) { calls++; environment }
        repeat(3) { assertTrue(tool.needsApproval(input)) }
        assertEquals(0, calls)
        val relay = access.copy(ownerInitiated = false, ownerThreadNonOwnerTurn = true)
        assertTrue(listOf(tool).withAgentRelayApproval(relay).single().needsApproval(input))
        tool.execute(input)
        assertEquals(1, calls)
    }

    @Test fun missingEnvironmentAutofillsAndExplicitValuesSurvive() = runTest {
        val result = execute(input)
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        val fields = decode(result.getValue("issue_url").jsonPrimitive.content)
        assertEquals("4.1.4", fields["app-version"])
        assertEquals("Android 15 (SDK 35)", fields["android-version"])
        assertEquals("Maker Phone", fields["device-model"])
        assertEquals("The owner opens this link to review and submit on GitHub; you cannot know whether it was submitted.", result["note"]?.jsonPrimitive?.content)
        val explicit = execute(JsonObject(input + mapOf("app_version" to JsonPrimitive("old"), "android_version" to JsonPrimitive(" "), "device_model" to JsonPrimitive("custom"))))
        val supplied = decode(explicit.getValue("issue_url").jsonPrimitive.content)
        assertEquals("old", supplied["app-version"]); assertEquals("custom", supplied["device-model"])
        assertEquals("Android 15 (SDK 35)", supplied["android-version"])
    }

    @Test fun validatesRequiredFieldsTypesUnknownKeysAndTitleLimitsBeforeReadingEnvironment() = runTest {
        val invalid = listOf<JsonElement>(
            JsonNull, JsonArray(emptyList()), JsonObject(input - "title"),
            JsonObject(input + ("title" to JsonPrimitive(" "))),
            JsonObject(input + ("title" to JsonPrimitive("字".repeat(81)))),
            JsonObject(input + ("title" to JsonPrimitive("bug: error"))),
            JsonObject(input + ("title" to JsonPrimitive(42))),
            JsonObject(input + ("logs" to JsonNull)),
            JsonObject(input + ("provider" to JsonArray(emptyList()))),
            JsonObject(input + ("other" to JsonPrimitive("unknown"))),
        ) + listOf("description", "actual", "expected").map { JsonObject(input + (it to JsonPrimitive(""))) }
        invalid.forEach { value ->
            val result = execute(value) { error("Invalid input must not read environment") }
            assertFalse(result.toString(), result.getValue("success").jsonPrimitive.boolean)
        }
        assertTrue(execute(JsonObject(input + ("title" to JsonPrimitive("🐳".repeat(80))))).getValue("success").jsonPrimitive.boolean)
    }

    @Test fun optInRegistersBothToolsForAnyTurnAndDefaultRegistersNeither() {
        assertTrue(createBugReportTools(emptyList(), access) { environment }.isEmpty())
        for (turn in listOf(access, access.copy(ownerInitiated = false, ownerThreadNonOwnerTurn = true))) {
            assertEquals(listOf("app_diagnostics", "draft_bug_report"), createBugReportTools(listOf(LocalToolOption.BugReport), turn) { environment }.map { it.name })
        }
    }
}
