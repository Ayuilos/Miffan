package me.ayuilos.miffan.data.bugreport

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Parameter names match draft_bug_report; environment query ids match the existing GitHub form. */
data class BugReportDraft(
    val title: String,
    val description: String,
    val actual: String,
    val expected: String,
    val logs: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val app_version: String? = null,
    val android_version: String? = null,
    val device_model: String? = null,
) {
    fun sanitized(): BugReportDraft = copy(
        title = redactDiagnosticText(title.trim()), description = redactDiagnosticText(description),
        actual = redactDiagnosticText(actual), expected = redactDiagnosticText(expected),
        logs = logs?.let(::redactDiagnosticText), provider = provider?.let(::redactDiagnosticText),
        model = model?.let(::redactDiagnosticText), app_version = app_version?.let(::redactDiagnosticText),
        android_version = android_version?.let(::redactDiagnosticText), device_model = device_model?.let(::redactDiagnosticText),
    )

    internal fun validate() {
        require(title.isNotBlank() && title.codePointCount(0, title.length) in 1..80) { "title must contain 1–80 characters" }
        require(!title.trimStart().startsWith("bug:", ignoreCase = true)) { "title must not include the bug: prefix" }
        require(description.isNotBlank()) { "description is required and must include reproduction steps" }
        require(actual.isNotBlank()) { "actual is required" }
        require(expected.isNotBlank()) { "expected is required" }
    }

    internal fun fields(): List<Pair<String, String?>> = listOf(
        "title" to "bug: $title", "description" to description, "actual" to actual,
        "expected" to expected, "logs" to logs, "provider" to provider, "model" to model,
        "app-version" to app_version, "android-version" to android_version, "device-model" to device_model,
    )
}

const val BUG_REPORT_URL_LIMIT = 7500
const val BUG_REPORT_TRUNCATION_NOTE = "（内容过长，已截断，请在 issue 中补充）"
private const val ISSUE_FORM_URL = "https://github.com/Ayuilos/Miffan/issues/new?template=bug-report.yml"

/** Pure, sanitized, percent-encoded URL. Oversized fields other than logs/description are rejected. */
fun buildBugReportIssueUrl(report: BugReportDraft): String {
    report.validate()
    var draft = report.sanitized()
    fun url(value: BugReportDraft): String = ISSUE_FORM_URL + value.fields()
        .filter { it.second != null }
        .joinToString("") { (key, text) -> "&$key=${URLEncoder.encode(text.orEmpty(), StandardCharsets.UTF_8.name())}" }
    for (field in listOf("logs", "description")) {
        if (url(draft).length <= BUG_REPORT_URL_LIMIT) break
        val original = (if (field == "logs") draft.logs else draft.description) ?: continue
        fun shortened(count: Int): BugReportDraft {
            val end = original.offsetByCodePoints(0, count)
            val text = original.substring(0, end) + BUG_REPORT_TRUNCATION_NOTE
            return if (field == "logs") draft.copy(logs = text) else draft.copy(description = text)
        }
        val empty = shortened(0)
        if (url(empty).length > BUG_REPORT_URL_LIMIT) {
            draft = empty
            continue
        }
        var low = 0
        var high = original.codePointCount(0, original.length)
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (url(shortened(mid)).length <= BUG_REPORT_URL_LIMIT) low = mid else high = mid - 1
        }
        draft = shortened(low)
    }
    return url(draft).also {
        require(it.length <= BUG_REPORT_URL_LIMIT) { "Other report fields exceed the URL limit; shorten actual, expected, provider or model" }
    }
}

/** Full sanitized copy; URL truncation does not discard the copyable report. */
fun BugReportDraft.toMarkdown(): String {
    validate()
    val safe = sanitized()
    val labels = listOf(
        "问题描述 | Problem Description" to safe.description,
        "实际行为 | Actual Behavior" to safe.actual,
        "期望行为 | Expected Behavior" to safe.expected,
        "日志 | Logs" to safe.logs,
        "使用的提供商 | Provider" to safe.provider,
        "使用的模型 | Model" to safe.model,
        "APP 版本 | App Version" to safe.app_version,
        "安卓版本 | Android Version" to safe.android_version,
        "手机型号 | Device Model" to safe.device_model,
    )
    return "# bug: ${safe.title}\n\n" + labels.filter { it.second != null }
        .joinToString("\n\n") { (label, value) -> "### $label\n\n$value" }
}
